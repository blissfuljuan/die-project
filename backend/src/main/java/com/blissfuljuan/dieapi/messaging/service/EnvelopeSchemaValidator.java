package com.blissfuljuan.dieapi.messaging.service;

import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

/** Validates envelope JSON against the shared schema (contracts/messaging, packaged on the classpath). */
@Component
public class EnvelopeSchemaValidator {

    private static final String SCHEMA = "/contracts/messaging/envelope-v1.schema.json";

    private final JsonSchema schema;

    public EnvelopeSchemaValidator() {
        try (InputStream in = EnvelopeSchemaValidator.class.getResourceAsStream(SCHEMA)) {
            if (in == null) {
                throw new IllegalStateException("Message schema missing from classpath: " + SCHEMA);
            }
            this.schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Human-readable schema violations; empty when valid. Throws a RuntimeException if the text is not JSON at all. */
    public List<String> validate(String json) {
        return schema.validate(json, InputFormat.JSON).stream().map(m -> m.getMessage()).sorted().toList();
    }
}
