package com.blissfuljuan.dieapi.roster.service.reader;

import com.blissfuljuan.dieapi.common.exception.ApiException;
import com.blissfuljuan.dieapi.roster.model.RosterFileType;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RosterReaderFactoryTest {

    private final CsvRosterReader csv = new CsvRosterReader(500);
    private final XlsxRosterReader xlsx = new XlsxRosterReader(500);
    private final RosterReaderFactory factory = new RosterReaderFactory(List.of(csv, xlsx));

    @Test
    void choosesTheReaderFromTheExtensionIgnoringCase() {
        assertThat(factory.forFile("roster.csv")).isSameAs(csv);
        assertThat(factory.forFile("ROSTER.XLSX")).isSameAs(xlsx);
    }

    @Test
    void unsupportedOrMissingExtensionsAreRefusedWith415() {
        for (String name : new String[] {"roster.xls", "roster", "", null}) {
            assertThatThrownBy(() -> factory.forFile(name)).isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getCode()).isEqualTo("UNSUPPORTED_FILE_TYPE");
                assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
            });
        }
    }

    @Test
    void failsAtStartupWhenATypeHasNoReader() {
        assertThatThrownBy(() -> new RosterReaderFactory(List.of(csv)))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining(RosterFileType.XLSX.name());
    }

    @Test
    void readersCheckContentNotJustTheExtension() {
        byte[] notAZip = "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> xlsx.read(notAZip))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("XLSX_INVALID"));
        assertThatThrownBy(() -> csv.read(new byte[] {'a', 0, 'b'}))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("FILE_NOT_TEXT"));
    }
}
