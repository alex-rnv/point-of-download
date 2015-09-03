package com.alexrnv.pod.downstream;

import com.alexrnv.pod.bean.ConfigBean;
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
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.streams.Pump;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * @author ARyazanov
 *         9/1/2015.
 */
public class DownloadClient extends AbstractVerticle {

    private static final Logger LOG = LoggerFactory.getLogger(DownloadClient.class);

    private final ConcurrentMap<String, CompletableFuture<HttpClientResponseBean>> cache = new ConcurrentHashMap<>();

    @Override
    public void start() throws Exception {

        final ConfigBean config = readConfig();
        final HttpClient client = vertx.createHttpClient();
        final EventBus eventBus = vertx.eventBus();

        final List<String> skipHeaders = Arrays.asList("Host", config.downloadHeader);

        eventBus.consumer(config.podTopic, message -> {
            JsonObject jsonObject = (JsonObject) message.body();
            HttpServerRequestBean upstreamRequest = HttpServerRequestBean.fromJsonObject(jsonObject);
            String requestedUrl = upstreamRequest.headers.get(config.downloadHeader);

            CompletableFuture<HttpClientResponseBean> result = cache.compute(requestedUrl, (url, val) -> {
                if (val != null) {
                    LOG.debug("Returning cached value for url " + url + ": " + val);
                    //System.out.println("Returning " + val);
                    return val;
                } else {
                    CompletableFuture<HttpClientResponseBean> r = new CompletableFuture<>();

                    HttpClientRequest clientRequest = client.getAbs(url);
                    copyHeaders(upstreamRequest, clientRequest, skipHeaders);

                    clientRequest
                            .handler(response -> {
                                response.pause();
                                final String fileName = getFileName(clientRequest, config);
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
//                                                response.headers().forEach(h -> {
//                                                    System.out.println(h.getKey() + ":" + h.getValue());
//                                                });
                                                asyncFile.flush().close(event -> {
                                                    if (event.succeeded()) {
                                                        r.complete(responseBean(response, fileName, config));
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
                    //System.out.println("Copy header " + k + ":" + v);
                    LOG.debug("Copy header " + k + ":" + v);
                    clientRequest.putHeader(k, v);
                });
            }


        });

        //clientRequest.putHeader("Accept-Encoding", "gzip");
        //clientRequest.putHeader("Connection", "keep-alive");
        //clientRequest.putHeader("Proxy-Connection", "keep-alive");
        //clientRequest.putHeader("Host", "servicetest.globalweathercorp.com");
        //clientRequest.putHeader("Accept", "*/*");
//        clientRequest.headers().forEach(h -> {
//            System.out.println(h.getKey() + ":" + h.getValue());
//        });
    }

    private String getFileName(HttpClientRequest clientRequest, ConfigBean config) {
        return config.cacheDir + "f" + RandomStringUtils.randomAlphanumeric(8) + "_" + StringUtils.substringAfterLast(clientRequest.uri(), "/");
    }

    private HttpClientResponseBean responseBean(HttpClientResponse response, String filename, ConfigBean config) {
        HttpClientResponseBean bean = new HttpClientResponseBean(response);
        bean.headers.add(config.resultHeader, filename);
        return bean;
    }

    private ConfigBean readConfig() {
        JsonObject json = null;
        try {
            json = vertx.getOrCreateContext().config();
            return new ConfigBean(json);
        } catch (Exception e) {
            LOG.error("Invalid config " + json, e);
            vertx.close();
            throw e;
        }
    }

}
