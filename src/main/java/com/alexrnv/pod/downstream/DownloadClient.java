package com.alexrnv.pod.downstream;

import com.alexrnv.pod.bean.HttpClientResponseBean;
import com.alexrnv.pod.bean.HttpServerRequestBean;
import com.alexrnv.pod.common.PODVerticle;
import io.vertx.core.VertxException;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.file.AsyncFile;
import io.vertx.core.file.OpenOptions;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.streams.Pump;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;

import static com.alexrnv.pod.http.Http.HTTP_DEFAULT_PORT;
import static org.apache.commons.lang3.RandomStringUtils.randomAlphanumeric;
import static org.apache.commons.lang3.StringUtils.substringAfterLast;

/**
 * Author Alex
 *         9/1/2015.
 */
public class DownloadClient extends PODVerticle {

    private static final Logger LOG = LoggerFactory.getLogger(DownloadClient.class);

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
            String requestedUrl = upstreamRequest.headers.get(config.downloadHeader);

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
            r.completeExceptionally(e);
        }

        if(!r.isDone()) {
            final List<String> skipHeaders = Arrays.asList("Host", config.downloadHeader);
            HttpClientRequest clientRequest = client.get(url.getPort(), url.getHost(), url.getFile());
            copyHeaders(upstreamRequest, clientRequest, skipHeaders);

            clientRequest
                    .handler(response -> {
                        HttpClientResponseBean rb = new HttpClientResponseBean(response);
                        LOG.info("Referrer response: " + rb);
                        response.pause();
                        final String fileName = getFileName(clientRequest);
                        OpenOptions openOptions = new OpenOptions().setCreate(true).setTruncateExisting(true);
                        vertx.fileSystem().open(fileName, openOptions, fileEvent -> {
                            if (fileEvent.failed()) {
                                LOG.error("Failed to open file " + fileName, fileEvent.cause());
                                r.completeExceptionally(fileEvent.cause());
                                return;
                            }

                            final AsyncFile asyncFile = fileEvent.result();
                            final Pump downloadPump = Pump.pump(response, asyncFile);

                            response.endHandler(e -> {
                                        asyncFile.flush().close(event -> {
                                            if (event.succeeded()) {
                                                updateResponseAndScheduleCleanup(rb, reqUrl, fileName);
                                                r.complete(rb);
                                            } else {
                                                LOG.error("Failed to close file " + fileName, event.cause());
                                                r.completeExceptionally(event.cause());
                                            }
                                        });
                                    }
                            ).exceptionHandler(t -> {
                                //"Connection was closed" exception fires every time, even when pump is fully read
                                if (t instanceof VertxException && t.getMessage().contains("Connection was closed")) {
                                    LOG.debug("", t);
                                } else {
                                    LOG.warn("", t);
                                }
                            });

                            downloadPump.start();
                            response.resume();
                        });
                    })
                    .exceptionHandler(t -> {
                        LOG.error("Failed to process request ", t);
                        if(retryCounter > 1) {
                            LOG.info("Retry, counter is " + retryCounter);
                            scheduler.schedule(() -> doRequestWithRetry(client, upstreamRequest, reqUrl, retryCounter - 1, r),
                                    config.retry.delayMs, TimeUnit.MILLISECONDS);
                        } else {
                            LOG.info("No more retries");
                            r.completeExceptionally(t);
                        }
                    })
                    .setTimeout(config.requestTimeoutMs)
                    .end();
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

    private String getFileName(HttpClientRequest clientRequest) {
        return config.cacheDir + "f" + randomAlphanumeric(8) + "_" + substringAfterLast(clientRequest.uri(), "/");
    }

    private void updateResponseAndScheduleCleanup(HttpClientResponseBean bean, String url, String filename) {
        bean.headers.add(config.resultHeader, filename);
        scheduler.schedule(() -> {
            cache.remove(url);
            try {
                Files.delete(Paths.get(config.cacheDir, filename));
            } catch (IOException e) {
                LOG.error("Failed to delete file", e);
            }
        }, config.ttlMin, TimeUnit.MINUTES);
    }

}
