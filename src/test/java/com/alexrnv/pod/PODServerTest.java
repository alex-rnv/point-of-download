package com.alexrnv.pod;

import com.alexrnv.pod.upstream.PODServer;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * @author ARyazanov
 *         9/1/2015.
 */
@RunWith(VertxUnitRunner.class)
public class PODServerTest {

    private Vertx vertx;

    @Before
    public void before(TestContext context) {
        vertx = Vertx.vertx();
        Async async = context.async();
        vertx.deployVerticle(PODServer.class.getName(), r -> {
            context.assertTrue(r.succeeded());
            async.complete();
        });
    }

    @After
    public void after(TestContext context) {
        vertx.close(context.asyncAssertSuccess());
    }

    @Test
    public void test1(TestContext context) {

        HttpClient client = vertx.createHttpClient();
        Async async = context.async();
        client.get(8070, "localhost", "/", resp ->
                resp.bodyHandler(body -> {
                    System.out.println(body.getBytes().length);
                    client.close();
                    async.complete();
                }).handler(r -> System.out.println("ggg")))
                .setChunked(true)
                .putHeader("Referer", "http://vertx.io:80/docs/vertx-core/java/")
                .write("Hi")
                .end();
    }

}