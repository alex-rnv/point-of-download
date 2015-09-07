package com.alexrnv.pod.bean;

import com.alexrnv.pod.json.WgetModule;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.io.IOException;
import java.util.List;

/**
 * Date: 9/2/2015
 * Time: 5:21 PM
 *
 * Author: Alex
 */
public class HttpClientResponseBean {
    private static final Logger LOG = LoggerFactory.getLogger(HttpClientResponseBean.class);

    @JsonIgnore
    private static ObjectMapper mapper = new ObjectMapper()
            .registerModule(new WgetModule());

    @JsonProperty
    public int statusCode;
    @JsonProperty
    public String statusMessage;
    @JsonProperty
    public MultiMap headers;
    @JsonProperty
    public MultiMap trailers;
    @JsonProperty
    public List<String> cookies;

    private HttpClientResponseBean(){}

    protected HttpClientResponseBean(int statusCode, String statusMessage, MultiMap headers, MultiMap trailers, List<String> cookies) {
        this.statusCode = statusCode;
        this.statusMessage = statusMessage;
        this.headers = headers;
        this.trailers = trailers;
        this.cookies = cookies;
    }

    public HttpClientResponseBean(HttpClientResponse response) {
        this(response.statusCode(), response.statusMessage(), response.headers(), response.trailers(), response.cookies());
    }

    public static HttpClientResponseBean fromJsonObject(JsonObject jsonObject) {
        String string = jsonObject.encode();
        try {
            return mapper.readValue(string, HttpClientResponseBean.class);
        } catch (IOException e) {
            LOG.error("Failed to read " + string, e);
        }
        return null;
    }

    public JsonObject asJsonObject() {
        try {
            return new JsonObject(mapper.writeValueAsString(this));
        } catch (JsonProcessingException e) {
            LOG.error("Failed to process " + this, e);
            return null;
        }
    }

    @Override
    public String toString() {
        return "HttpClientResponseBean{" +
                "statusCode=" + statusCode +
                ", statusMessage='" + statusMessage + '\'' +
                ", headers=" + BeanUtil.toString(headers) +
                ", trailers=" + BeanUtil.toString(trailers) +
                ", cookies=" + cookies +
                '}';
    }
}
