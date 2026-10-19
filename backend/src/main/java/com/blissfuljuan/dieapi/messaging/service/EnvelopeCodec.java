package com.blissfuljuan.dieapi.messaging.service;

import com.blissfuljuan.dieapi.messaging.model.Envelope;
import com.blissfuljuan.dieapi.messaging.model.InvalidEnvelopeException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Envelope to and from UTF-8 JSON. Both directions are checked against the shared JSON Schema. */
@Component
public class EnvelopeCodec {

    private final ObjectMapper mapper;
    private final EnvelopeSchemaValidator validator;

    public EnvelopeCodec(ObjectMapper mapper, EnvelopeSchemaValidator validator) {
        this.mapper = mapper;
        this.validator = validator;
    }

    public String encode(Envelope envelope) {
        String json;
        try {
            json = mapper.writeValueAsString(envelope);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Envelope is not serializable", e);
        }
        List<String> problems = validator.validate(json);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("Envelope violates the v1 schema: " + problems);
        }
        return json;
    }

    public Envelope decode(byte[] body) {
        String json = new String(body, StandardCharsets.UTF_8);
        List<String> problems;
        try {
            problems = validator.validate(json);
        } catch (RuntimeException e) {
            throw new InvalidEnvelopeException("Message body is not valid JSON", List.of("not JSON"));
        }
        if (!problems.isEmpty()) {
            throw new InvalidEnvelopeException("Message violates the envelope schema", problems);
        }
        try {
            return mapper.readValue(json, Envelope.class);
        } catch (JacksonException e) {
            throw new InvalidEnvelopeException("Message could not be mapped", List.of("unmappable"));
        }
    }
}
