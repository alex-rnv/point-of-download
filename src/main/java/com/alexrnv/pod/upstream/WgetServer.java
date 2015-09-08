package com.alexrnv.pod.upstream;

import com.alexrnv.pod.bean.HttpClientResponseBean;
import com.alexrnv.pod.bean.HttpServerRequestBean;
import com.alexrnv.pod.common.WgetVerticle;
import com.alexrnv.pod.downstream.DownloadClient;
import com.alexrnv.pod.http.Http;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.alexrnv.pod.http.Http.HTTP_CODE_INTERNAL_SERVER_ERROR;
import static com.alexrnv.pod.http.Http.HTTP_CODE_METHOD_NOT_ALLOWED;

/**
 * Author Alex
 *         9/1/2015.
 */
public class WgetServer extends WgetVerticle {

    private static final Logger LOG = LoggerFactory.getLogger(WgetServer.class);

    private final List<HttpMethod> allowedMethods = Collections.singletonList(HttpMethod.GET);
    @Override
    public void start0() {
        final List<String> skipHeaders = Arrays.asList("Host", config.downloadHeader);

        DeploymentOptions downloaderOptions = new DeploymentOptions()
                .setConfig(vertx.getOrCreateContext().config())
                .setInstances(1);

        vertx.deployVerticle(DownloadClient.class.getName(), downloaderOptions, h -> {
            if(h.failed()) {
                LOG.error("Failed to deploy " + DownloadClient.class.getName(), h.cause());
                vertx.close();
            }
        });

        vertx.createHttpServer().requestHandler(request -> {
            HttpServerRequestBean requestBean = new HttpServerRequestBean(request);
            LOG.info("Received request: " + requestBean);
            HttpMethod method = request.method();
            if (!allowedMethods.contains(request.method())) {
                LOG.info("Not allowed method " + method);
                request.response()
                        .setStatusCode(HTTP_CODE_METHOD_NOT_ALLOWED)
                        .putHeader("Allow", StringUtils.join(allowedMethods, ","))
                        .end();
            } else {
                //serialize headers and params
                JsonObject jsonRequest = new HttpServerRequestBean(request).asJsonObject();
                if (jsonRequest == null) {
                    request.response()
                            .setStatusCode(HTTP_CODE_INTERNAL_SERVER_ERROR)
                            .end();
                } else {
                    DeliveryOptions options = new DeliveryOptions().setSendTimeout(config.requestTimeoutMs);
                    vertx.eventBus().send(config.podTopic, jsonRequest, options, r -> {
                        HttpServerResponse response = request.response();
                        if(r.failed()) {
                            LOG.error("Internal error", r.cause());
                            response.setStatusCode(HTTP_CODE_INTERNAL_SERVER_ERROR).end();
                        } else if (r.result() == null || r.result().body() == null) {
                            LOG.error("Internal error: empty response in event bus");
                            response.setStatusCode(HTTP_CODE_INTERNAL_SERVER_ERROR).end();
                        } else {
                            JsonObject jsonObject = (JsonObject) r.result().body();
                            HttpClientResponseBean responseBean = HttpClientResponseBean.fromJsonObject(jsonObject);
                            copyHeaders(responseBean, response, skipHeaders);
                            if (Http.isCodeOk(responseBean.statusCode)) {
                                String fileName = responseBean.headers.get(config.resultHeader);
                                response.sendFile(fileName);
                            } else {
                                response.setStatusCode(responseBean.statusCode)
                                        .setStatusMessage(responseBean.statusMessage)
                                        .end();
                            }
                        }
                    });
                }
            }
        }).listen(config.upstream.port, config.upstream.host);
    }

    private void copyHeaders(HttpClientResponseBean responseBean, HttpServerResponse response, List<String> skipHeaders) {
        responseBean.headers.names().forEach(k -> {
            if (!skipHeaders.contains(k)) {
                responseBean.headers.getAll(k).forEach(v -> {
                    LOG.debug("Copy header " + k + ":" + v);
                    response.putHeader(k, v);
                });
            }
        });
    }

}
