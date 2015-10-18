package com.alexrnv.pod.upstream;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
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
import java.util.Random;

/**
 * Author Alex
 *         9/1/2015.
 */
@RunWith(VertxUnitRunner.class)
public class WgetServerTest {

    public static final int DOWNLOAD_SERVER_PORT = 8070;//same as test json cfg
    private static final int REMOTE_SERVER_PORT = 8060;

    private Vertx vertx;
    private HttpServer server;

    @Before
    public void before(TestContext context) {
        vertx = Vertx.vertx();
        server = vertx.createHttpServer();
        server.requestHandler(r -> {
            if(r.uri().endsWith("/error")) {
                r.response().setStatusCode(500).end();
            } else if(r.uri().endsWith("/100000000")) {
                byte[] bytes = new byte[100000000];
                new Random().nextBytes(bytes);
                Buffer buf = Buffer.buffer(bytes);
                r.response().end(buf);
            } else if (r.uri().endsWith("/4")) {
                r.response().end("xxxx");
            } else if (r.uri().endsWith("/2")) {
                r.response().end("xx");
            } else {
                r.response().end("x");
            }
        }).listen(REMOTE_SERVER_PORT, "localhost", r -> context.assertTrue(r.succeeded()));
    }

    @After
    public void after(TestContext context) {
        vertx.close(context.asyncAssertSuccess());
    }

    @Test
    public void testSameUrlOneClient(TestContext context) throws IOException {
        HttpClient client = vertx.createHttpClient();
        Async async = context.async();
        DeploymentOptions options = new DeploymentOptions().setConfig(readConfig("test_cfg.json"));
        vertx.deployVerticle(WgetServer.class.getName(), options, r -> {
            context.assertTrue(r.succeeded());
            async.complete();
        });
        int n = 10;
        while(n-- > 0) {
            sendAndCheckResponseLength(client, context, 1);
        }
    }

    @Test
    public void testSameUrlDifferentClients(TestContext context) throws IOException {
        Async async = context.async();
        DeploymentOptions options = new DeploymentOptions().setConfig(readConfig("test_cfg.json"));
        vertx.deployVerticle(WgetServer.class.getName(), options, r -> {
            context.assertTrue(r.succeeded());
            async.complete();
        });

        int n = 10;
        while(n-- > 0) {
            sendAndCheckResponseLength(context, 2);
        }
    }

    @Test
    public void testDifferentUrls(TestContext context) throws IOException {
        Async async = context.async();
        DeploymentOptions options = new DeploymentOptions().setConfig(readConfig("test_cfg.json"));
        vertx.deployVerticle(WgetServer.class.getName(), options, r -> {
            context.assertTrue(r.succeeded());
            async.complete();
        });
        sendAndCheckResponseLength(context, 1);
        sendAndCheckResponseLength(context, 2);
        sendAndCheckResponseLength(context, 4);
        sendAndCheckResponseLength(context, 100000000);
    }

    private void sendAndCheckResponseLength(TestContext context, int len) throws IOException {
        sendAndCheckResponseLength(null, context, len);
    }

    private void sendAndCheckResponseLength(HttpClient client0, TestContext context, int len) throws IOException {
        HttpClient client = client0 != null ? client0 : vertx.createHttpClient();
        Async async = context.async();
        client.get(DOWNLOAD_SERVER_PORT, "localhost", "/", resp ->
                resp.bodyHandler(body -> {
                    context.assertEquals(len, body.getBytes().length);
                    if(client0 == null) client.close();
                    async.complete();
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