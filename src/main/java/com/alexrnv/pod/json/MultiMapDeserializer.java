package com.alexrnv.pod.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import io.vertx.core.MultiMap;
import io.vertx.core.http.CaseInsensitiveHeaders;

import java.io.IOException;

/**
 * Date: 9/2/2015
 * Time: 3:55 PM
 *
 * Author: Alex
 */
public class MultiMapDeserializer extends JsonDeserializer<MultiMap> {
    @Override
    public MultiMap deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        MultiMap multiMap = new CaseInsensitiveHeaders();
        while (p.nextToken() != JsonToken.END_OBJECT) {
            String key = p.getCurrentName();
            p.nextToken();
            while (p.nextToken() != JsonToken.END_ARRAY) {
                multiMap.add(key, p.getText());
            }
        }
        return multiMap;
    }
}
