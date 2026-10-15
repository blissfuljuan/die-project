package com.blissfuljuan.dieapi.roster.service;

import com.blissfuljuan.dieapi.roster.model.NormalizedRow;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Turns an uploaded CSV/XLSX roster into normalized rows. The upload is untrusted:
 *  - type is decided by extension AND content (UTF-8 text without NUL bytes; real ZIP container),
 *  - size and row counts are capped,
 *  - spreadsheet formulas are never evaluated (the row is rejected), every cell is read as text,
 *  - cells that spreadsheets would treat as formulas are rejected so they cannot reach a later export,
 *  - only the first sheet is read; macros/external links are never touched (POI does not execute them).
 * Parsing problems of a single row never abort the upload: the row is returned as invalid.
 */
@Component
public class RosterParser {

    public enum Field { STUDENT_NUMBER, LAST_NAME, FIRST_NAME, MIDDLE_NAME, EMAIL, PROGRAM, SECTION }

    private record GridRow(int rowNumber, List<String> cells, boolean hasFormula) {}

    private static final Logger log = LoggerFactory.getLogger(RosterParser.class);
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int MAX_COLUMNS = 30;
    private static final int MAX_CELL_CHARS = 500;
    private static final int MAX_STORED_CHARS = 200;

    private static final Map<String, Field> HEADERS = new HashMap<>();
    static {
        for (String h : List.of("studentid", "studentnumber", "studentidnumber", "idnumber", "id", "studentno", "studentidno")) HEADERS.put(h, Field.STUDENT_NUMBER);
        for (String h : List.of("lastname", "surname", "familyname")) HEADERS.put(h, Field.LAST_NAME);
        for (String h : List.of("firstname", "givenname", "forename")) HEADERS.put(h, Field.FIRST_NAME);
        for (String h : List.of("middlename", "middleinitial", "mi")) HEADERS.put(h, Field.MIDDLE_NAME);
        for (String h : List.of("email", "emailaddress")) HEADERS.put(h, Field.EMAIL);
        for (String h : List.of("program", "course", "programcourse")) HEADERS.put(h, Field.PROGRAM);
        for (String h : List.of("section")) HEADERS.put(h, Field.SECTION);
    }

    /** Limits applied to the unpacked workbook before Apache POI is allowed to load it. */
    private static final int MAX_ZIP_ENTRIES = 200;
    private static final long MAX_UNPACKED_BYTES = 30L * 1024 * 1024;
    private static final long MAX_ENTRY_BYTES = 20L * 1024 * 1024;

    static {
        // POI-level defence in depth (its default compression-ratio check stays on; tightening it rejects
        // ordinary small workbooks). preflightXlsx() already bounds what POI is ever allowed to unpack.
        ZipSecureFile.setMaxEntrySize(MAX_ENTRY_BYTES);
        ZipSecureFile.setMaxTextSize(10L * 1024 * 1024);
    }

    private final int maxRows;
    private final long maxBytes;

    public RosterParser(@Value("${die.roster.max-rows:500}") int maxRows,
                        @Value("${die.roster.max-bytes:5242880}") long maxBytes) {
        this.maxRows = maxRows;
        this.maxBytes = maxBytes;
    }

    public List<NormalizedRow> parse(String fileName, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw bad(HttpStatus.BAD_REQUEST, "The file is empty");
        }
        if (bytes.length > maxBytes) {
            throw bad(HttpStatus.PAYLOAD_TOO_LARGE, "The file is larger than " + (maxBytes / 1024 / 1024) + " MB");
        }
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        List<GridRow> grid;
        if (name.endsWith(".csv")) {
            grid = readCsv(bytes);
        } else if (name.endsWith(".xlsx")) {
            grid = readXlsx(bytes);
        } else {
            throw bad(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Only .csv and .xlsx files are supported");
        }
        return toRows(grid);
    }

    // ---- readers ----

    private List<GridRow> readCsv(byte[] bytes) {
        for (byte b : bytes) {
            if (b == 0) throw bad(HttpStatus.BAD_REQUEST, "The file is not a text CSV file");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw bad(HttpStatus.BAD_REQUEST, "The CSV file must be UTF-8 encoded");
        }
        if (text.startsWith("﻿")) text = text.substring(1);

        char delimiter = detectDelimiter(text);
        CSVFormat format = CSVFormat.DEFAULT.builder().setDelimiter(delimiter).setIgnoreEmptyLines(false)
            .setTrim(true).build();
        List<GridRow> rows = new ArrayList<>();
        try (CSVParser parser = CSVParser.parse(new StringReader(text), format)) {
            for (CSVRecord record : parser) {
                if (record.size() > MAX_COLUMNS) throw bad(HttpStatus.BAD_REQUEST, "Too many columns");
                List<String> cells = new ArrayList<>(record.size());
                for (String v : record) cells.add(cap(v));
                if (cells.stream().allMatch(String::isBlank)) continue;   // blank lines never count toward the cap
                rows.add(new GridRow((int) record.getRecordNumber(), cells, false));
                if (rows.size() > maxRows * 4 + 10) throw bad(HttpStatus.BAD_REQUEST, "Too many rows (maximum " + maxRows + ")");
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof ResponseStatusException r) throw r;
            log.debug("CSV parse failure", e);
            throw bad(HttpStatus.BAD_REQUEST, "The CSV file could not be read (check quotes and delimiters)");
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

    private List<GridRow> readXlsx(byte[] bytes) {
        if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K' || bytes[2] != 3 || bytes[3] != 4) {
            throw bad(HttpStatus.BAD_REQUEST, "The file is not a valid .xlsx workbook");
        }
        preflightXlsx(bytes);
        List<GridRow> rows = new ArrayList<>();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            if (workbook.getNumberOfSheets() == 0) throw bad(HttpStatus.BAD_REQUEST, "The workbook has no sheets");
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet.getLastRowNum() > maxRows * 4 + 10) {
                throw bad(HttpStatus.BAD_REQUEST, "Too many rows (maximum " + maxRows + ")");
            }
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            for (int r = Math.max(sheet.getFirstRowNum(), 0); r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                List<String> cells = new ArrayList<>();
                boolean formula = false;
                if (row != null) {
                    int last = Math.min(row.getLastCellNum(), MAX_COLUMNS);
                    for (int c = 0; c < last; c++) {
                        Cell cell = row.getCell(c);
                        if (cell == null) {
                            cells.add("");
                        } else if (cell.getCellType() == CellType.FORMULA) {
                            formula = true; // never evaluated, never trusted
                            cells.add("");
                        } else {
                            cells.add(cap(formatter.formatCellValue(cell).trim()));
                        }
                    }
                }
                rows.add(new GridRow(r + 1, cells, formula));
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.debug("XLSX parse failure", e);
            throw bad(HttpStatus.BAD_REQUEST, "The workbook could not be read");
        }
        return rows;
    }

    /**
     * Streams the ZIP container once, discarding the content, to enforce limits BEFORE Apache POI builds its
     * in-memory model: entry count, total unpacked size, and the number of rows declared by each worksheet.
     * A few-hundred-KB "zip bomb" or a sheet with a million empty rows is rejected here in constant memory.
     */
    private void preflightXlsx(byte[] bytes) {
        long total = 0;
        int entries = 0;
        int rowLimit = maxRows * 4 + 10;
        byte[] buffer = new byte[16 * 1024];
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ZIP_ENTRIES) throw bad(HttpStatus.BAD_REQUEST, "The workbook has too many parts");
                boolean worksheet = entry.getName().startsWith("xl/worksheets/") && entry.getName().endsWith(".xml");
                long entryBytes = 0;
                int rows = 0;
                int matched = 0;                       // progress through the marker "<row " across buffer boundaries
                int read;
                while ((read = zip.read(buffer)) > 0) {
                    entryBytes += read;
                    total += read;
                    if (entryBytes > MAX_ENTRY_BYTES || total > MAX_UNPACKED_BYTES) {
                        throw bad(HttpStatus.BAD_REQUEST, "The workbook is too large once unpacked");
                    }
                    if (worksheet) {
                        for (int i = 0; i < read; i++) {
                            byte b = buffer[i];
                            if (b == ROW_MARKER[matched]) {
                                if (++matched == ROW_MARKER.length) {
                                    matched = 0;
                                    if (++rows > rowLimit) throw bad(HttpStatus.BAD_REQUEST, "Too many rows (maximum " + maxRows + ")");
                                }
                            } else {
                                matched = b == ROW_MARKER[0] ? 1 : 0;
                            }
                        }
                    }
                }
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            log.debug("XLSX preflight failure", e);
            throw bad(HttpStatus.BAD_REQUEST, "The workbook could not be read");
        }
    }

    private static final byte[] ROW_MARKER = "<row ".getBytes(StandardCharsets.US_ASCII);

    // ---- header mapping and row normalization ----

    private List<NormalizedRow> toRows(List<GridRow> grid) {
        int headerIndex = -1;
        for (int i = 0; i < grid.size(); i++) {
            if (grid.get(i).cells().stream().anyMatch(c -> !c.isBlank())) { headerIndex = i; break; }
        }
        if (headerIndex < 0) throw bad(HttpStatus.BAD_REQUEST, "The file has no header row");

        Map<Field, Integer> columns = new EnumMap<>(Field.class);
        List<String> header = grid.get(headerIndex).cells();
        for (int c = 0; c < header.size(); c++) {
            Field f = HEADERS.get(header.get(c).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""));
            if (f == null) continue;
            if (columns.putIfAbsent(f, c) != null) {
                throw bad(HttpStatus.BAD_REQUEST, "Duplicate column for " + label(f));
            }
        }
        List<String> missing = new ArrayList<>();
        for (Field f : List.of(Field.STUDENT_NUMBER, Field.LAST_NAME, Field.FIRST_NAME)) {
            if (!columns.containsKey(f)) missing.add(label(f));
        }
        if (!missing.isEmpty()) {
            throw bad(HttpStatus.BAD_REQUEST, "Missing required column(s): " + String.join(", ", missing));
        }

        List<NormalizedRow> rows = new ArrayList<>();
        for (int i = headerIndex + 1; i < grid.size(); i++) {
            GridRow g = grid.get(i);
            if (g.cells().stream().allMatch(String::isBlank) && !g.hasFormula()) continue;
            if (rows.size() >= maxRows) {
                throw bad(HttpStatus.BAD_REQUEST, "Too many rows (maximum " + maxRows + " per import)");
            }
            rows.add(normalize(g, columns));
        }
        if (rows.isEmpty()) throw bad(HttpStatus.BAD_REQUEST, "The file has no data rows");
        return rows;
    }

    private NormalizedRow normalize(GridRow g, Map<Field, Integer> columns) {
        String number = value(g, columns, Field.STUDENT_NUMBER);
        String last = value(g, columns, Field.LAST_NAME);
        String first = value(g, columns, Field.FIRST_NAME);
        String middle = value(g, columns, Field.MIDDLE_NAME);
        String email = value(g, columns, Field.EMAIL);
        String program = value(g, columns, Field.PROGRAM);
        String section = value(g, columns, Field.SECTION);
        String lowerEmail = email == null ? null : email.toLowerCase(Locale.ROOT);

        String problem = null;
        if (g.hasFormula()) problem = "Spreadsheet formulas are not allowed";
        else if (number == null) problem = "Student ID is required";
        else if (last == null) problem = "Last name is required";
        else if (first == null) problem = "First name is required";
        else if (number.length() > 64) problem = "Student ID is too long";
        else if (hasUnsafeText(number, last, first, middle, program, section, email)) {
            problem = "A cell starts with =, +, @ (treated as a formula by spreadsheets) or contains control characters";
        } else if (last.length() > 100 || first.length() > 100 || (middle != null && middle.length() > 100)
            || (program != null && program.length() > 100) || (section != null && section.length() > 100)) {
            problem = "A value is longer than 100 characters";
        } else if (lowerEmail != null && (lowerEmail.length() > 254 || !EMAIL.matcher(lowerEmail).matches())) {
            problem = "Email address is not valid";
        }
        return new NormalizedRow(g.rowNumber(), stored(number), stored(last), stored(first), stored(middle),
            stored(lowerEmail), stored(program), stored(section), problem);
    }

    private static boolean hasUnsafeText(String... values) {
        for (String v : values) {
            if (v == null) continue;
            char first = v.charAt(0);
            if (first == '=' || first == '+' || first == '@' || first == '\t' || first == '\r') return true;
            // '-' is fine inside identifiers but not at the start of a text cell.
            if (first == '-' && !v.matches("-?\\d+")) return true;
            for (int i = 0; i < v.length(); i++) {
                if (Character.isISOControl(v.charAt(i))) return true;
            }
        }
        return false;
    }

    private static String value(GridRow g, Map<Field, Integer> columns, Field f) {
        Integer idx = columns.get(f);
        if (idx == null || idx >= g.cells().size()) return null;
        String v = Normalizer.normalize(g.cells().get(idx), Normalizer.Form.NFC).strip().replaceAll("\\s+", " ");
        return v.isEmpty() ? null : v;
    }

    private static String stored(String v) {
        return v == null ? null : (v.length() > MAX_STORED_CHARS ? v.substring(0, MAX_STORED_CHARS) : v);
    }

    private static String cap(String v) {
        return v == null ? "" : (v.length() > MAX_CELL_CHARS ? v.substring(0, MAX_CELL_CHARS) : v);
    }

    private static String label(Field f) {
        return switch (f) {
            case STUDENT_NUMBER -> "Student ID";
            case LAST_NAME -> "Last Name";
            case FIRST_NAME -> "First Name";
            case MIDDLE_NAME -> "Middle Name";
            case EMAIL -> "Email";
            case PROGRAM -> "Program";
            case SECTION -> "Section";
        };
    }

    private static ResponseStatusException bad(HttpStatus status, String reason) {
        return new ResponseStatusException(status, reason);
    }
}
