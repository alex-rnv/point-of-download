package com.alexrnv.pod.common;

import com.alexrnv.pod.bean.ConfigBean;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

/**
 * Date: 9/4/2015
 * Time: 11:23 AM
 *
 * Author: Alex
 */
public abstract class WgetVerticle extends AbstractVerticle {

    private static final Logger LOG = LoggerFactory.getLogger(WgetVerticle.class);

    protected ConfigBean config;

    @Override
    public void start() throws Exception {
        config = readConfig();
        start0();
    }

    protected abstract void start0();

    protected ConfigBean readConfig() {
        JsonObject json = null;
        try {
            json = vertx.getOrCreateContext().config();
            return new ConfigBean(json);
        } catch (Exception e) {
            LOG.error("Invalid config " + json, e);
            vertx.close();
            throw e;
        }
    }
}
