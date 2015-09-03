package com.alexrnv.pod.downstream;

import com.alexrnv.pod.bean.HttpClientResponseBean;
import com.alexrnv.pod.bean.HttpServerRequestBean;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.file.AsyncFile;
import io.vertx.core.file.OpenOptions;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.Pump;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * @author ARyazanov
 *         9/1/2015.
 */
public class DownloadClient extends AbstractVerticle {

    private final ConcurrentMap<String, CompletableFuture<HttpClientResponseBean>> cache = new ConcurrentHashMap<>();

    @Override
    public void start() throws Exception {
        HttpClient client = vertx.createHttpClient();
        EventBus eventBus = vertx.eventBus();

        eventBus.consumer("downloader", message -> {
            JsonObject jsonObject = (JsonObject) message.body();
            HttpServerRequestBean upstreamRequest = HttpServerRequestBean.fromJsonObject(jsonObject);
            String requestedUrl = upstreamRequest.headers.get("Referer");

            CompletableFuture<HttpClientResponseBean> result = cache.compute(requestedUrl, (url, val) -> {
                if (val != null) {
                    return val;
                } else {
                    CompletableFuture<HttpClientResponseBean> r = new CompletableFuture<>();

                    HttpClientRequest clientRequest = client.getAbs(url);
                    copyHeaders(upstreamRequest, clientRequest);

                    clientRequest.handler(response -> {
                                response.pause();
                                final String fileName = System.getProperty("java.io.tmpdir") + "/" + UUID.randomUUID().toString();
                                OpenOptions openOptions = new OpenOptions().setCreate(true).setTruncateExisting(true);
                                vertx.fileSystem().open(fileName, openOptions, fileEvent -> {
                                    if (fileEvent.failed()) {
                                        r.completeExceptionally(fileEvent.cause());
                                        return;
                                    }

                                    final AsyncFile asyncFile = fileEvent.result();
                                    final Pump downloadPump = Pump.pump(response, asyncFile);

                                    response.endHandler(e -> asyncFile.flush().close(event -> {
                                                if (event.succeeded()) {
                                                    r.complete(responseBean(response, fileName));
                                                } else {
                                                    r.completeExceptionally(event.cause());
                                                }
                                            })
                                    ).exceptionHandler(t -> {
                                        r.completeExceptionally(t);
                                    });

                                    downloadPump.start();
                                    response.resume();
                                });
                            })
                            .exceptionHandler(t -> {
                                r.completeExceptionally(t);
                            })
                            .setChunked(true)
                            .setTimeout(120000)
                            .end();
                    return r;
                }
            });

            result.thenAcceptAsync(v -> {
                message.reply(v.asJsonObject());
            });
        });

    }

    private void copyHeaders(HttpServerRequestBean upstreamRequest, HttpClientRequest clientRequest) {
        upstreamRequest.headers.names().forEach(k -> {
            if(!"Host".equals(k) && !"Referer".equals(k)) {
                upstreamRequest.headers.getAll(k).forEach(v -> {
                    clientRequest.putHeader(k, v);
                });
            }
            clientRequest.putHeader("Host", "http://vertx.io:80");
        });
    }

    private HttpClientResponseBean responseBean(HttpClientResponse response, String filename) {
        HttpClientResponseBean bean = new HttpClientResponseBean(response);
        bean.headers.add("Location", filename);
        return bean;
    }

}
