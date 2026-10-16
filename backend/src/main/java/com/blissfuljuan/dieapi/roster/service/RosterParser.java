package com.blissfuljuan.dieapi.roster.service;

import com.blissfuljuan.dieapi.common.exception.ApiException;
import com.blissfuljuan.dieapi.roster.model.GridRow;
import com.blissfuljuan.dieapi.roster.model.NormalizedRow;
import com.blissfuljuan.dieapi.roster.model.rule.RosterRowValidator;
import com.blissfuljuan.dieapi.roster.service.reader.RosterReaderFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Turns an uploaded CSV/XLSX roster into normalized rows. The upload is untrusted. This class owns the checks
 * shared by every format (not empty, size cap); the format-specific reading and hardening lives in the
 * {@code reader} package and the per-row rules in {@link RosterRowValidator}.
 * Parsing problems of a single row never abort the upload: the row is returned as invalid.
 */
@Component
public class RosterParser {

    private final RosterReaderFactory readers;
    private final RosterRowValidator validator;
    private final long maxBytes;

    public RosterParser(RosterReaderFactory readers,
                        @Value("${die.roster.max-rows:500}") int maxRows,
                        @Value("${die.roster.max-bytes:5242880}") long maxBytes) {
        this.readers = readers;
        this.validator = new RosterRowValidator(maxRows);
        this.maxBytes = maxBytes;
    }

    public List<NormalizedRow> parse(String fileName, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw ApiException.badRequest("FILE_EMPTY", "The file is empty");
        }
        if (bytes.length > maxBytes) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE",
                "The file is larger than " + (maxBytes / 1024 / 1024) + " MB");
        }
        List<GridRow> grid = readers.forFile(fileName).read(bytes);
        return validator.validate(grid);
    }

    /** Strips any path and control characters from a client-supplied file name; never empty, at most 200 chars. */
    public static String cleanFileName(String raw) {
        String name = raw == null ? "" : raw;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1)
            .replaceAll("[\\p{Cntrl}]", "").trim();
        if (name.isEmpty()) name = "roster";
        return name.length() > 200 ? name.substring(name.length() - 200) : name;
    }
}
