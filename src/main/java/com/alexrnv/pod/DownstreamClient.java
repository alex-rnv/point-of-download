package com.alexrnv.pod;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.file.AsyncFile;
import io.vertx.core.file.OpenOptions;
import io.vertx.core.http.HttpClient;
import io.vertx.core.streams.Pump;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * @author ARyazanov
 *         9/1/2015.
 */
public class DownstreamClient extends AbstractVerticle {

    private final ConcurrentMap<String, CompletableFuture<String>> cache = new ConcurrentHashMap<>();

    @Override
    public void start() throws Exception {
        HttpClient client = vertx.createHttpClient();
        EventBus eventBus = vertx.eventBus();
        eventBus.consumer("downloader", message -> {
            String requestedUrl = (String) message.body();
            CompletableFuture<String> result = cache.compute(requestedUrl, (url, val) -> {
                if (val != null) {
                    message.reply(val);
                    return val;
                } else {
                    CompletableFuture<String> r = new CompletableFuture<>();
                    client.getAbs(url)
                            .handler(response -> {
                                response.pause();
                                final String fileName = System.getProperty("java.io.tmpdir") + "/GGG";
                                vertx.fileSystem().open(fileName, new OpenOptions()
                                                .setCreate(true)
                                                .setTruncateExisting(true),
                                        fileEvent -> {
                                            if (fileEvent.failed()) {
                                                fileEvent.cause().printStackTrace();
                                                return;
                                            }
                                            final AsyncFile asynFile = fileEvent.result();

                                            final Pump downloadPump = Pump.pump(response, asynFile);
                                            response.endHandler(event -> asynFile.flush().close(event1 -> {
                                                System.out.println(downloadPump.numberPumped());
                                                if (event1.succeeded()) {
                                                    r.complete(fileName);
                                                }
                                            }));
                                            downloadPump.start();
                                            response.resume();

                                        });
                            }).exceptionHandler(t -> r.completeExceptionally(t))
                            .setChunked(true).setTimeout(1800000).end();
                    return r;
                }
            });

            result.thenAcceptAsync(v -> message.reply(v));

        });

    }
}
