package com.alexrnv.pod;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.eventbus.DeliveryOptions;

/**
 * @author ARyazanov
 *         9/1/2015.
 */
public class DownloadServer extends AbstractVerticle {

    @Override
    public void start() throws Exception {

        DeploymentOptions downloaderOptions = new DeploymentOptions()
                .setInstances(1);

        vertx.deployVerticle(DownstreamClient.class.getName(), downloaderOptions, h -> {
            if(h.failed()) {
                System.exit(1);
            }
        });

        vertx.createHttpServer().requestHandler(h -> {
            String targetUrl = h.getHeader("Referer");
            vertx.eventBus().send("downloader", targetUrl, new DeliveryOptions().setSendTimeout(1800000), r -> {
                h.response().sendFile((String) r.result().body());
            });
        }).listen(8070, "localhost");
    }
}
