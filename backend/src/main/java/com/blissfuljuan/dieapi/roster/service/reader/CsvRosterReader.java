package com.blissfuljuan.dieapi.roster.service.reader;

import com.blissfuljuan.dieapi.common.exception.ApiException;
import com.blissfuljuan.dieapi.common.exception.ErrorCode;
import com.blissfuljuan.dieapi.roster.model.GridRow;
import com.blissfuljuan.dieapi.roster.model.RosterFileType;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static com.blissfuljuan.dieapi.roster.model.rule.RosterLimits.MAX_COLUMNS;
import static com.blissfuljuan.dieapi.roster.model.rule.RosterLimits.cap;
import static com.blissfuljuan.dieapi.roster.model.rule.RosterLimits.rawRowLimit;

/** CSV: UTF-8 text without NUL bytes; comma, semicolon or tab separated; blank lines never count toward the row cap. */
@Component
public class CsvRosterReader implements RosterFileReader {

    private static final Logger log = LoggerFactory.getLogger(CsvRosterReader.class);

    private final int maxRows;

    public CsvRosterReader(@Value("${die.roster.max-rows:500}") int maxRows) {
        this.maxRows = maxRows;
    }

    @Override
    public RosterFileType type() {
        return RosterFileType.CSV;
    }

    @Override
    public List<GridRow> read(byte[] bytes) {
        for (byte b : bytes) {
            if (b == 0) throw ApiException.badRequest(ErrorCode.FILE_NOT_TEXT, "The file is not a text CSV file");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw ApiException.badRequest(ErrorCode.CSV_NOT_UTF8, "The CSV file must be UTF-8 encoded");
        }
        if (text.startsWith("﻿")) text = text.substring(1);

        char delimiter = detectDelimiter(text);
        CSVFormat format = CSVFormat.DEFAULT.builder().setDelimiter(delimiter).setIgnoreEmptyLines(false)
            .setTrim(true).build();
        List<GridRow> rows = new ArrayList<>();
        try (CSVParser parser = CSVParser.parse(new StringReader(text), format)) {
            for (CSVRecord record : parser) {
                if (record.size() > MAX_COLUMNS) throw ApiException.badRequest(ErrorCode.TOO_MANY_COLUMNS, "Too many columns");
                List<String> cells = new ArrayList<>(record.size());
                for (String v : record) cells.add(cap(v));
                if (cells.stream().allMatch(String::isBlank)) continue;   // blank lines never count toward the cap
                rows.add(new GridRow((int) record.getRecordNumber(), cells, false));
                if (rows.size() > rawRowLimit(maxRows)) {
                    throw ApiException.badRequest(ErrorCode.TOO_MANY_ROWS, "Too many rows (maximum " + maxRows + ")");
                }
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof ApiException r) throw r;
            log.debug("CSV parse failure", e);
            throw ApiException.badRequest(ErrorCode.CSV_UNREADABLE, "The CSV file could not be read (check quotes and delimiters)");
        }
        return rows;
    }

    private static char detectDelimiter(String text) {
        int end = text.indexOf('\n');
        String first = end < 0 ? text : text.substring(0, end);
        int commas = count(first, ','), semis = count(first, ';'), tabs = count(first, '\t');
        if (tabs > commas && tabs >= semis) return '\t';
        if (semis > commas) return ';';
        return ',';
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }
}
