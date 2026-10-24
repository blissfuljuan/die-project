package com.blissfuljuan.die.orchestrator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Envelope to and from UTF-8 JSON, both directions validated against the shared JSON Schema. */
public final class EnvelopeCodec {

    private static final String SCHEMA = "/contracts/messaging/envelope-v1.schema.json";

    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonSchema schema;

    public EnvelopeCodec() {
        try (InputStream in = EnvelopeCodec.class.getResourceAsStream(SCHEMA)) {
            if (in == null) {
                throw new IllegalStateException("Message schema missing from classpath: " + SCHEMA);
            }
            this.schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<String> validate(String json) {
        return schema.validate(json, InputFormat.JSON).stream().map(m -> m.getMessage()).sorted().toList();
    }

    public byte[] encode(Envelope envelope) {
        String json;
        try {
            json = mapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Envelope is not serializable", e);
        }
        List<String> problems = validate(json);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("Envelope violates the v1 schema: " + problems);
        }
        return json.getBytes(StandardCharsets.UTF_8);
    }

    public Envelope decode(byte[] body) {
        String json = new String(body, StandardCharsets.UTF_8);
        List<String> problems;
        try {
            problems = validate(json);
        } catch (RuntimeException e) {
            throw new InvalidEnvelopeException("Message body is not valid JSON", List.of("not JSON"));
        }
        if (!problems.isEmpty()) {
            throw new InvalidEnvelopeException("Message violates the envelope schema", problems);
        }
        try {
            return mapper.readValue(json, Envelope.class);
        } catch (JsonProcessingException e) {
            throw new InvalidEnvelopeException("Message could not be mapped", List.of("unmappable"));
        }
    }
}
