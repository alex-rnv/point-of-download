package com.alexrnv.pod;

import com.alexrnv.pod.upstream.PODServer;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.apache.commons.io.IOUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.io.InputStream;

/**
 * @author ARyazanov
 *         9/1/2015.
 */
@RunWith(VertxUnitRunner.class)
public class PODServerTest {

    private Vertx vertx;
    private HttpClient client;
    private HttpServer server;

    @Before
    public void before(TestContext context) {
        vertx = Vertx.vertx();
        client = vertx.createHttpClient();
        server = vertx.createHttpServer();
        server.requestHandler(r -> {
            if (r.uri().endsWith("/4")) {
                r.response().end("xxxx");
            } else if (r.uri().endsWith("/2")) {
                r.response().end("xx");
            } else {
                r.response().end("x");
            }
        }).listen(8060, "localhost", r -> context.assertTrue(r.succeeded()));
    }

    @After
    public void after(TestContext context) {
        vertx.close(context.asyncAssertSuccess());
    }

    @Test
    public void testDownloadLength(TestContext context) throws IOException {
        Async async1 = context.async();
        DeploymentOptions options = new DeploymentOptions().setConfig(readConfig("cfg.json"));
        vertx.deployVerticle(PODServer.class.getName(), options, r -> {
            context.assertTrue(r.succeeded());
            async1.complete();
        });
        testLength(context, 1);
        testLength(context, 2);
        testLength(context, 4);
    }

    private void testLength(TestContext context, int len) throws IOException {
        Async async2 = context.async();
        client.get(8070, "localhost", "/", resp ->
                resp.bodyHandler(body -> {
                    context.assertEquals(len, body.getBytes().length);
                    async2.complete();
                }))
                .setChunked(true)
                .putHeader("Referer", "http://localhost:8060/" + len)
                .end();
    }

    private JsonObject readConfig(String name) throws IOException {
        InputStream is = this.getClass().getClassLoader().getResourceAsStream(name);
        return new JsonObject(IOUtils.toString(is));
    }

}