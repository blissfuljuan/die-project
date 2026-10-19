package com.blissfuljuan.dieapi.messaging;

import com.blissfuljuan.dieapi.messaging.model.Envelope;
import com.blissfuljuan.dieapi.messaging.model.InvalidEnvelopeException;
import com.blissfuljuan.dieapi.messaging.service.EnvelopeCodec;
import com.blissfuljuan.dieapi.messaging.service.EnvelopeSchemaValidator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shared contract: contracts/messaging/envelope-v1.schema.json and its example corpus are the same files the
 * Java worker and the Python worker test against.
 */
class EnvelopeContractTest {

    private static final Path CONTRACTS = Path.of(System.getProperty("die.contracts.dir", "../contracts/messaging"));

    private final EnvelopeSchemaValidator validator = new EnvelopeSchemaValidator();
    private final EnvelopeCodec codec = new EnvelopeCodec(JsonMapper.builder().build(), validator);

    private static List<Path> files(String dir) throws IOException {
        try (Stream<Path> s = Files.list(CONTRACTS.resolve("examples").resolve(dir))) {
            return s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
    }

    @Test
    void everyValidExampleIsAcceptedAndDecodes() throws IOException {
        assertThat(files("valid")).hasSizeGreaterThanOrEqualTo(6);
        for (Path p : files("valid")) {
            assertThat(validator.validate(Files.readString(p))).as(p.getFileName().toString()).isEmpty();
            Envelope e = codec.decode(Files.readAllBytes(p));
            assertThat(e.envelopeVersion()).isEqualTo(1);
            assertThat(e.messageId()).isNotNull();
        }
    }

    @Test
    void everyInvalidExampleIsRejected() throws IOException {
        assertThat(files("invalid")).hasSizeGreaterThanOrEqualTo(8);
        for (Path p : files("invalid")) {
            assertThat(validator.validate(Files.readString(p))).as(p.getFileName().toString()).isNotEmpty();
            assertThatThrownBy(() -> codec.decode(Files.readAllBytes(p))).isInstanceOf(InvalidEnvelopeException.class);
        }
    }

    @Test
    void garbageIsAnInvalidEnvelopeNotACrash() {
        assertThatThrownBy(() -> codec.decode("{not json".getBytes())).isInstanceOf(InvalidEnvelopeException.class);
        assertThatThrownBy(() -> codec.decode(new byte[] {(byte) 0xff, 0x00})).isInstanceOf(InvalidEnvelopeException.class);
    }

    @Test
    void encodedEnvelopesFollowTheSchemaAndRoundTrip() {
        Envelope in = new Envelope(1, UUID.randomUUID(), "req-12345678", "test.ping",
            Instant.parse("2026-10-18T09:30:00.123Z"), 1,
            Map.of("testJobId", UUID.randomUUID().toString(), "note", "x", "simulateFailure", false));
        String json = codec.encode(in);
        assertThat(json).contains("\"createdAt\":\"2026-10-18T09:30:00.123Z\"");
        assertThat(codec.decode(json.getBytes())).isEqualTo(in);
    }

    @Test
    void anEnvelopeThatBreaksTheSchemaCannotBeEncoded() {
        Envelope bad = new Envelope(1, UUID.randomUUID(), "x", "test.ping", Instant.now(), 1, Map.of());
        assertThatThrownBy(() -> codec.encode(bad)).isInstanceOf(IllegalArgumentException.class);
    }
}
