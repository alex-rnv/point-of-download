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
 * Time: 12:43 PM
 *
 * Author: Alex
 */
public class HttpClientResponseBeanTest {

    private final String json = "{\"statusCode\":200,\"statusMessage\":\"Ok\",\"headers\":{\"h1\":[\"v1\",\"v11\",\"v1111\"],\"h2\":[\"v2\"]},\"trailers\":{\"p1\":[\"v1\",\"v2\"]},\"cookies\":[\"cookie1\",\"cookie2\"]}";

    @Test
    public void testMarshall() {
        MultiMap headers = new CaseInsensitiveHeaders();
        headers.add("h1", "v1");
        headers.add("h1", "v11");
        headers.add("h1", "v1111");
        headers.add("h2", "v2");

        MultiMap trailers = new CaseInsensitiveHeaders();
        trailers.add("p1", "v1");
        trailers.add("p1", "v2");

        HttpClientResponseBean bean = new HttpClientResponseBean(200, "Ok", headers, trailers, Arrays.asList("cookie1", "cookie2"));
        assertJsonEquals(json, bean.asJsonObject().toString());
    }

    @Test
    public void testUnmarshall() {
        HttpClientResponseBean bean = HttpClientResponseBean.fromJsonObject(new JsonObject(json));
        assertEquals(Arrays.asList("v1", "v11", "v1111"), bean.headers.getAll("h1"));
        assertEquals(Collections.singletonList("v2"), bean.headers.getAll("h2"));
        assertEquals(Arrays.asList("v1", "v2"), bean.trailers.getAll("p1"));
        assertEquals(Collections.emptyList(), bean.trailers.getAll("p2"));
        assertEquals(Arrays.asList("cookie1", "cookie2"), bean.cookies);
        assertEquals("Ok", bean.statusMessage);
        assertEquals(200, bean.statusCode);
    }

}