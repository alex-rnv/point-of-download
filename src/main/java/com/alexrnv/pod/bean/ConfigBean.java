package com.alexrnv.pod.bean;

import io.vertx.core.json.JsonObject;

import static java.lang.System.getProperty;
import static java.util.Objects.requireNonNull;

/**
 * @author ARyazanov
 *         9/3/2015.
 */
public class ConfigBean {
    public final long requestTimeoutMs;
    public final String downloadHeader;
    public final String resultHeader;
    public final String podTopic;
    public final String cacheDir;
    public final Upstream upstream;

    public ConfigBean(JsonObject json) {
        this.requestTimeoutMs = requireNonNull(json.getLong("requestTimeoutMs"));
        this.downloadHeader = requireNonNull(json.getString("downloadHeader"));
        this.resultHeader = requireNonNull(json.getString("resultHeader"));
        this.podTopic = requireNonNull(json.getString("podTopic"));
        this.cacheDir = resolvePath(json.getString("cacheDir"));
        JsonObject ups = requireNonNull(json.getJsonObject("upstream"));
        this.upstream = new Upstream(requireNonNull(ups.getString("host")), requireNonNull(ups.getInteger("port")));
    }

    private static String resolvePath(String path) {
        return requireNonNull(path).replace("%t", getProperty("java.io.tmpdir"));
    }

    public static class Upstream {
        public final String host;
        public final int port;

        public Upstream(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }
}
