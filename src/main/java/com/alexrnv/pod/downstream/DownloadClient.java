package com.alexrnv.pod.downstream;

import com.alexrnv.pod.bean.HttpClientResponseBean;
import com.alexrnv.pod.bean.HttpServerRequestBean;
import com.alexrnv.pod.common.PODVerticle;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.file.AsyncFile;
import io.vertx.core.file.OpenOptions;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.streams.Pump;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;

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

        final List<String> skipHeaders = Arrays.asList("Host", config.downloadHeader);

        eventBus.consumer(config.podTopic, message -> {
            JsonObject jsonObject = (JsonObject) message.body();
            HttpServerRequestBean upstreamRequest = HttpServerRequestBean.fromJsonObject(jsonObject);
            String requestedUrl = upstreamRequest.headers.get(config.downloadHeader);

            CompletableFuture<HttpClientResponseBean> result = cache.compute(requestedUrl, (url, val) -> {
                if (val != null) {
                    LOG.info("Returning cached future for url " + url + ": " + val);
                    return val;
                } else {
                    CompletableFuture<HttpClientResponseBean> r = new CompletableFuture<>();

                    HttpClientRequest clientRequest = client.getAbs(url);
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
                                                        updateResponseAndScheduleCleanup(rb, url, fileName);
                                                        r.complete(rb);
                                                    } else {
                                                        LOG.error("Failed to close file " + fileName, event.cause());
                                                        r.completeExceptionally(event.cause());
                                                    }
                                                });
                                            }
                                    ).exceptionHandler(t -> {
                                        //"connection closed" exception fires every time, even when pump is fully read
                                        LOG.warn("", t);
                                    });

                                    downloadPump.start();
                                    response.resume();
                                });
                            })
                            .exceptionHandler(t -> {
                                LOG.error("Failed to process request ", t);
                                r.completeExceptionally(t);
                            })
                            .setTimeout(config.requestTimeoutMs)
                            .end();
                    return r;
                }
            });

            result.handleAsync((v,t) -> v)
                    .thenAccept(v -> message.reply(v != null ? v.asJsonObject() : null));
        });

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

    private String getFileName(HttpClientRequest clientRequest) {
        return config.cacheDir + "f" + RandomStringUtils.randomAlphanumeric(8) + "_" + StringUtils.substringAfterLast(clientRequest.uri(), "/");
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
