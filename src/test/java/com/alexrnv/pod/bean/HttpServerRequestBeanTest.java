package com.alexrnv.pod.bean;

import io.vertx.core.MultiMap;
import io.vertx.core.http.CaseInsensitiveHeaders;
import io.vertx.core.json.JsonObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static net.javacrumbs.jsonunit.JsonAssert.assertJsonEquals;
import static org.junit.Assert.assertEquals;

/**
 * Date: 9/4/2015
 * Time: 12:15 PM
 *
 * Author: Alex
 */
public class HttpServerRequestBeanTest {

    private final String json = "{\"id\":\"id\", \"absoluteUri\":\"http://a:80/b/c?x=y\",\"uri\":\"/b/c?x=y\",\"path\":\"/b/c\",\"query\":\"x=y\",\"headers\":{\"h1\":[\"v1\",\"v11\",\"v1111\"],\"h2\":[\"v2\"]},\"params\":{\"p1\":[\"v1\",\"v2\"]}}";

    @Test
    public void testMarshall() {
        MultiMap headers = new CaseInsensitiveHeaders();
        headers.add("h1", "v1");
        headers.add("h1", "v11");
        headers.add("h1", "v1111");
        headers.add("h2", "v2");

        MultiMap params = new CaseInsensitiveHeaders();
        params.add("p1", "v1");
        params.add("p1", "v2");

        HttpServerRequestBean bean = new HttpServerRequestBean("id", "http://a:80/b/c?x=y", "/b/c?x=y", "/b/c", "x=y", headers, params);
        assertJsonEquals(json, bean.asJsonObject().toString());
    }

    @Test
    public void testUnmarshall() {
        HttpServerRequestBean bean = HttpServerRequestBean.fromJsonObject(new JsonObject(json));
        assertEquals(Arrays.asList("v1", "v11", "v1111"), bean.headers.getAll("h1"));
        assertEquals(Collections.singletonList("v2"), bean.headers.getAll("h2"));
        assertEquals(Arrays.asList("v1", "v2"), bean.params.getAll("p1"));
        assertEquals(Collections.emptyList(), bean.params.getAll("p2"));
        assertEquals("http://a:80/b/c?x=y", bean.absoluteUri);
        assertEquals("/b/c?x=y", bean.uri);
        assertEquals("/b/c", bean.path);
        assertEquals("x=y", bean.query);
    }

}