package com.alexrnv.pod.upstream;

import com.alexrnv.pod.bean.HttpClientResponseBean;
import com.alexrnv.pod.downstream.DownloadClient;
import com.alexrnv.pod.bean.HttpServerRequestBean;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.apache.commons.lang3.StringUtils;

import java.util.Collections;
import java.util.List;

import static com.alexrnv.pod.http.HttpCodes.HTTP_CODE_INTERNAL_SERVER_ERROR;
import static com.alexrnv.pod.http.HttpCodes.HTTP_CODE_METHOD_NOT_ALLOWED;

/**
 * @author ARyazanov
 *         9/1/2015.
 */
public class PODServer extends AbstractVerticle {

    private static final Logger LOG = LoggerFactory.getLogger(PODServer.class);

    private final List<HttpMethod> allowedMethods = Collections.singletonList(HttpMethod.GET);
    @Override
    public void start() throws Exception {

        DeploymentOptions downloaderOptions = new DeploymentOptions()
                .setInstances(1);

        vertx.deployVerticle(DownloadClient.class.getName(), downloaderOptions, h -> {
            if(h.failed()) {
                LOG.error("Failed to deploy " + DownloadClient.class.getName(), h.cause());
                vertx.close();
            }
        });

        vertx.createHttpServer().requestHandler(request -> {
            HttpMethod method = request.method();
            if(!allowedMethods.contains(request.method())) {
                LOG.info("Not allowed method " + method);
                request.response()
                        .setStatusCode(HTTP_CODE_METHOD_NOT_ALLOWED)
                        .putHeader("Allow", StringUtils.join(allowedMethods, ","))
                        .end();
            } else {
                //serialize headers and params
                JsonObject jsonRequest = new HttpServerRequestBean(request).asJsonObject();
                if(jsonRequest == null) {
                    request.response()
                            .setStatusCode(HTTP_CODE_INTERNAL_SERVER_ERROR)
                            .end();
                } else {
                    vertx.eventBus().send("downloader", jsonRequest, new DeliveryOptions().setSendTimeout(180000), r -> {
                        HttpServerResponse response = request.response();
                        JsonObject jsonObject = (JsonObject) r.result().body();
                        if(jsonObject == null) {
                            response.setStatusCode(HTTP_CODE_INTERNAL_SERVER_ERROR).end();
                        } else {
                            HttpClientResponseBean responseBean = HttpClientResponseBean.fromJsonObject(jsonObject);
                            String fileName = responseBean.headers.get("Location");
                            response.sendFile(fileName);
                        }
                    });
                }
            }
        }).listen(8070, "localhost");
    }
}
