package com.alexrnv.pod.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import io.vertx.core.MultiMap;

import java.io.IOException;

/**
 * Date: 9/2/2015
 * Time: 1:51 PM
 *
 * @author: Alex
 */
public class MultiMapSerializer extends JsonSerializer<MultiMap> {
    @Override
    public void serialize(MultiMap value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        gen.writeStartObject();
        for(String k : value.names()) {
            gen.writeObjectField(k, value.getAll(k));
        }
        gen.writeEndObject();
    }
}
