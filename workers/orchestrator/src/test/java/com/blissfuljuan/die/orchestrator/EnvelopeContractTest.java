package com.blissfuljuan.die.orchestrator;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The same schema and example corpus the API and the Python worker test against (contracts/messaging). */
class EnvelopeContractTest {

    private static final Path CONTRACTS = Path.of(System.getProperty("die.contracts.dir", "../../contracts/messaging"));

    private final EnvelopeCodec codec = new EnvelopeCodec();

    private static List<Path> files(String dir) throws IOException {
        try (Stream<Path> s = Files.list(CONTRACTS.resolve("examples").resolve(dir))) {
            return s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
    }

    @Test
    void everyValidExampleIsAcceptedAndDecodes() throws IOException {
        assertThat(files("valid")).hasSizeGreaterThanOrEqualTo(6);
        for (Path p : files("valid")) {
            assertThat(codec.validate(Files.readString(p))).as(p.getFileName().toString()).isEmpty();
            assertThat(codec.decode(Files.readAllBytes(p)).envelopeVersion()).isEqualTo(1);
        }
    }

    @Test
    void everyInvalidExampleIsRejected() throws IOException {
        assertThat(files("invalid")).hasSizeGreaterThanOrEqualTo(8);
        for (Path p : files("invalid")) {
            assertThat(codec.validate(Files.readString(p))).as(p.getFileName().toString()).isNotEmpty();
            assertThatThrownBy(() -> codec.decode(Files.readAllBytes(p))).isInstanceOf(InvalidEnvelopeException.class);
        }
    }

    @Test
    void garbageIsAnInvalidEnvelopeNotACrash() {
        assertThatThrownBy(() -> codec.decode("{not json".getBytes())).isInstanceOf(InvalidEnvelopeException.class);
        assertThatThrownBy(() -> codec.decode(new byte[] {(byte) 0xff, 0x00})).isInstanceOf(InvalidEnvelopeException.class);
    }

    @Test
    void encodedEnvelopesRoundTrip() {
        Envelope in = new Envelope(1, "a1b2c3d4-0000-4000-8000-000000000001", "req-12345678", "test.ping",
            "2026-10-18T09:30:00.123Z", 1,
            Map.of("testJobId", "3f2b8c1e-5d4a-4e7b-9a60-1c2d3e4f5a6b", "note", "x", "simulateFailure", false));
        assertThat(codec.decode(codec.encode(in))).isEqualTo(in);
    }

    @Test
    void anEnvelopeThatBreaksTheSchemaCannotBeEncoded() {
        Envelope bad = new Envelope(1, "nope", "x", "test.ping", "now", 1, Map.of());
        assertThatThrownBy(() -> codec.encode(bad)).isInstanceOf(IllegalArgumentException.class);
    }
}
