package com.alexrnv.pod.downstream;

import com.alexrnv.pod.bean.HttpClientResponseBean;
import com.alexrnv.pod.bean.HttpServerRequestBean;
import com.alexrnv.pod.common.WgetVerticle;
import com.alexrnv.pod.http.Http;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.file.AsyncFile;
import io.vertx.core.file.OpenOptions;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.streams.Pump;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;

import static com.alexrnv.pod.http.Http.HTTP_DEFAULT_PORT;
import static java.util.Objects.requireNonNull;
import static org.apache.commons.lang3.StringUtils.substringAfterLast;

/**
 * Given multiple download requests, caches future result and sends one http-get request to target server,
 * and notifies asynchronously all recipients when response comes. Requests come from internal event bus.
 * Received files are cached in file-system, and deleted when configured ttl timeout expires. All subsequent
 * requests are served with cached local file, until it is removed.
 */
public class DownloadClient extends WgetVerticle {

    private static final Logger LOG = LoggerFactory.getLogger(DownloadClient.class);

    private static final int ENSURE_RESPONSE_PAUSE_MS = 1000;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        return t;
    });

    private final ConcurrentMap<String, CompletableFuture<HttpClientResponseBean>> cache = new ConcurrentHashMap<>();

    @Override
    public void start0() {
        final HttpClient client = vertx.createHttpClient();
        final EventBus eventBus = vertx.eventBus();

        eventBus.consumer(config.podTopic, message -> {
            JsonObject jsonObject = (JsonObject) message.body();
            HttpServerRequestBean upstreamRequest = HttpServerRequestBean.fromJsonObject(jsonObject);
            if(upstreamRequest == null) {
                message.reply(null);
                return;
            }

            String requestedUrl = upstreamRequest.headers.get(config.downloadHeader);

            if(requestedUrl == null) {
                LOG.error("Download header " + config.downloadHeader + " is not set for " + upstreamRequest.id);
                message.reply(null);
                return;
            }

            CompletableFuture<HttpClientResponseBean> result = cache.compute(requestedUrl, (reqUrl, val) -> {
                if (val != null) {
                    LOG.info("Returning cached future for url " + reqUrl + ": " + val);
                    return val;
                } else {
                    CompletableFuture<HttpClientResponseBean> r = new CompletableFuture<>();
                    doRequestWithRetry(client, upstreamRequest, reqUrl, config.retry.numRetries, r);
                    return r;
                }
            });

            result.handleAsync((v, t) -> v)
                    .thenAccept(v -> message.reply(v != null ? v.asJsonObject() : null));
        });

    }

    private void doRequestWithRetry(HttpClient client, HttpServerRequestBean upstreamRequest,
                                    String reqUrl, final int retryCounter, CompletableFuture<HttpClientResponseBean> r) {

        URL url = null;
        try {
            url = resolveReferringUrl(reqUrl);
        } catch (MalformedURLException e) {
            LOG.error("Wrong url", e);
            complete(r, reqUrl, null, e);
        }

        if (!r.isDone()) {
            final List<String> skipHeaders = Arrays.asList("Host", config.downloadHeader);
            HttpClientRequest clientRequest = client.get(url.getPort(), url.getHost(), url.getFile());
            copyHeaders(upstreamRequest, clientRequest, skipHeaders);

            clientRequest
                    .handler(response -> {
                        HttpClientResponseBean rb = new HttpClientResponseBean(response);
                        LOG.info("Referrer response for " + upstreamRequest.id + ": " + rb);
                        if(!Http.isCodeOk(response.statusCode())) {
                            complete(r, reqUrl, rb, null);
                        }  else {
                            response.pause();
                            final String fileName = getFileName(clientRequest, upstreamRequest.id);

                            OpenOptions openOptions = new OpenOptions()
                                    .setCreate(true)
                                    .setTruncateExisting(true);
                            vertx.fileSystem().open(fileName, openOptions, fileEvent -> {
                                if (fileEvent.failed()) {
                                    LOG.error("Failed to open file " + fileName, fileEvent.cause());
                                    complete(r, reqUrl, null, fileEvent.cause());
                                    return;
                                }

                                final AsyncFile asyncFile = fileEvent.result();
                                final Pump downloadPump = Pump.pump(response, asyncFile);

                                response.endHandler(e -> {
                                    LOG.debug("End handler called for " + upstreamRequest.id);
                                    //add filename header for server verticle
                                    rb.headers.add(config.resultHeader, fileName);
                                    closeAsyncFileAndComplete(asyncFile, fileName, rb, r, true, null);

                                }).exceptionHandler(t -> {
                                    LOG.debug("Exception handler called for " + upstreamRequest.id);
                                    if (retryCounter > 1) {
                                        LOG.info("Retry, counter is " + retryCounter + " for " + upstreamRequest.id);
                                        scheduler.schedule(() -> doRequestWithRetry(client, upstreamRequest, reqUrl, retryCounter - 1, r),
                                                config.retry.delayMs, TimeUnit.MILLISECONDS);
                                        asyncFile.close();//close
                                    } else {
                                        LOG.info("No more retries for " + upstreamRequest.id);
                                        closeAsyncFileAndComplete(asyncFile, fileName, rb, r, false, reqUrl);
                                    }
                                });

                                //safe net, in case response never come
                                scheduler.schedule(() -> ensureComplete(reqUrl),
                                        config.requestTimeoutMs + ENSURE_RESPONSE_PAUSE_MS,
                                        TimeUnit.MILLISECONDS);

                                downloadPump.start();
                                response.resume();
                            });

                            //clean cached value and delete file when ttl hit
                            cleanCachedFile(reqUrl, fileName, config.ttlMin, TimeUnit.MINUTES);
                        }
                    })
                    .exceptionHandler(t -> {
                        LOG.error("Failed to process request ", t);
                        complete(r, reqUrl, null, t);
                    })
                    .setTimeout(config.requestTimeoutMs)
                    .end();
        }
    }

    /**
     * @param file to close
     * @param name file name
     * @param rb request bean
     * @param r future
     * @param waitFile - if true, complete future only after file is closed, async otherwise
     */
    private void closeAsyncFileAndComplete(AsyncFile file, String name, HttpClientResponseBean rb,
                                           CompletableFuture<HttpClientResponseBean> r, boolean waitFile, String reqUrl) {
        file.flush().close(event -> {
            LOG.debug("Closing file " + name);
            if (event.failed()) {
                LOG.error("Failed to close file " + name, event.cause());
            }
            if(waitFile) {
                //complete successfully
                complete(r, reqUrl, rb, null);
            }
        });
        if(!waitFile) {
            complete(r, reqUrl, rb, null);
        }
    }

    private void copyHeaders(HttpServerRequestBean upstreamRequest, HttpClientRequest clientRequest, List<String> skipHeaders) {
        upstreamRequest.headers.names().forEach(k -> {
            if (!skipHeaders.contains(k)) {
                upstreamRequest.headers.getAll(k).forEach(v -> {
                    LOG.debug("Copy header " + k + ":" + v);
                    clientRequest.putHeader(k, v);
                });
            }
        });
    }

    private URL resolveReferringUrl(String reqUrl) throws MalformedURLException {
        URL url = new URL(reqUrl);
        if (url.getPort() == -1) {
            //todo: https support
            //if("https".equalsIgnoreCase(url.getProtocol())) {
            //    return new URL(url.getProtocol(), url.getHost(), HTTPS_DEFAULT_PORT, url.getFile());
            //} else {
                return new URL(url.getProtocol(), url.getHost(), HTTP_DEFAULT_PORT, url.getFile());
            //}
        } else {
            return url;
        }
    }

    private String getFileName(HttpClientRequest clientRequest, String id) {
        return config.cacheDir + "f" + id + "_" + substringAfterLast(clientRequest.uri(), "/");
    }

    private void cleanCachedFile(String url, String filename, int delay, TimeUnit timeUnit) {
        scheduler.schedule(() -> {
            //remove result from cache
            ensureComplete(url);
            cache.remove(url);
            //delete from file system
            LOG.info("Deleting cached file " + filename);
            vertx.fileSystem().delete(filename, event -> {
                if (event.succeeded()) {
                    LOG.info("Deleted: " + filename);
                } else {
                    LOG.error("Failed to delete", event.cause());
                }
            });
        }, delay, timeUnit);
    }

    private void ensureComplete(String url) {
        CompletableFuture<HttpClientResponseBean> future = cache.get(url);
        if (future != null && !future.isDone()) {
            LOG.warn("Design error, future was not completed in time");
            complete(future, url, null, new TimeoutException("Request timed out"));
        }
    }

    /**
     * Logic is not really straightforward, completion type depends on input parameters.
     * One and only one of 'bean' or 'cause' should be present.
     * @param future - task to complete.
     * @param url - if present, it means url should be deleted from cache shortly.
     * @param bean - if present, it means normal completion, and this is a completion result.
     * @param cause - if present, it means exceptional completion, and this is a cause.
     */
    private void complete(CompletableFuture<HttpClientResponseBean> future,
                          String url,
                          HttpClientResponseBean bean,
                          Throwable cause) {
        if(requireNonNull(future).isDone()) {
            LOG.warn("Future is already completed");
            return;
        }
        if(url != null) {
            //allow failed future to live one retry cycle in cache, and delete it to allow future download attempts
            scheduler.schedule(() -> {
                LOG.debug("Deleting url " + url + " from cache");
                cache.remove(url);
            }, config.retry.delayMs, TimeUnit.MILLISECONDS);
        }
        if(bean != null) {
            future.complete(bean);
        }
        if(cause != null) {
            future.completeExceptionally(cause);
        }
    }
}
