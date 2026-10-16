package com.blissfuljuan.dieapi.roster.model.rule;

import com.blissfuljuan.dieapi.common.exception.ApiException;
import com.blissfuljuan.dieapi.common.exception.ErrorCode;
import com.blissfuljuan.dieapi.roster.model.GridRow;
import com.blissfuljuan.dieapi.roster.model.NormalizedRow;
import com.blissfuljuan.dieapi.roster.model.RosterField;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import static com.blissfuljuan.dieapi.roster.model.rule.RosterLimits.MAX_STORED_CHARS;

/**
 * Maps the header row of a raw grid to roster columns and turns each data row into a cleaned
 * {@link NormalizedRow}. A problem with one row never aborts the upload: the row carries an
 * {@code invalidReason}. Problems with the file as a whole (no header, missing or duplicate columns,
 * no data, too many rows) throw {@link ApiException}.
 */
public final class RosterRowValidator {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private static final Map<String, RosterField> HEADERS = new HashMap<>();
    static {
        for (String h : List.of("studentid", "studentnumber", "studentidnumber", "idnumber", "id", "studentno", "studentidno")) HEADERS.put(h, RosterField.STUDENT_NUMBER);
        for (String h : List.of("lastname", "surname", "familyname")) HEADERS.put(h, RosterField.LAST_NAME);
        for (String h : List.of("firstname", "givenname", "forename")) HEADERS.put(h, RosterField.FIRST_NAME);
        for (String h : List.of("middlename", "middleinitial", "mi")) HEADERS.put(h, RosterField.MIDDLE_NAME);
        for (String h : List.of("email", "emailaddress")) HEADERS.put(h, RosterField.EMAIL);
        for (String h : List.of("program", "course", "programcourse")) HEADERS.put(h, RosterField.PROGRAM);
        for (String h : List.of("section")) HEADERS.put(h, RosterField.SECTION);
    }

    private final int maxRows;

    public RosterRowValidator(int maxRows) {
        this.maxRows = maxRows;
    }

    public List<NormalizedRow> validate(List<GridRow> grid) {
        int headerIndex = -1;
        for (int i = 0; i < grid.size(); i++) {
            if (grid.get(i).cells().stream().anyMatch(c -> !c.isBlank())) { headerIndex = i; break; }
        }
        if (headerIndex < 0) throw bad(ErrorCode.HEADER_ROW_MISSING, "The file has no header row");

        Map<RosterField, Integer> columns = mapHeader(grid.get(headerIndex).cells());

        List<NormalizedRow> rows = new ArrayList<>();
        for (int i = headerIndex + 1; i < grid.size(); i++) {
            GridRow g = grid.get(i);
            if (g.cells().stream().allMatch(String::isBlank) && !g.hasFormula()) continue;
            if (rows.size() >= maxRows) {
                throw bad(ErrorCode.TOO_MANY_ROWS, "Too many rows (maximum " + maxRows + " per import)");
            }
            rows.add(normalize(g, columns));
        }
        if (rows.isEmpty()) throw bad(ErrorCode.NO_DATA_ROWS, "The file has no data rows");
        return rows;
    }

    private static Map<RosterField, Integer> mapHeader(List<String> header) {
        Map<RosterField, Integer> columns = new EnumMap<>(RosterField.class);
        for (int c = 0; c < header.size(); c++) {
            RosterField f = HEADERS.get(header.get(c).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""));
            if (f == null) continue;
            if (columns.putIfAbsent(f, c) != null) {
                throw bad(ErrorCode.DUPLICATE_COLUMN, "Duplicate column for " + f.label());
            }
        }
        List<String> missing = new ArrayList<>();
        for (RosterField f : List.of(RosterField.STUDENT_NUMBER, RosterField.LAST_NAME, RosterField.FIRST_NAME)) {
            if (!columns.containsKey(f)) missing.add(f.label());
        }
        if (!missing.isEmpty()) {
            throw bad(ErrorCode.MISSING_COLUMNS, "Missing required column(s): " + String.join(", ", missing));
        }
        return columns;
    }

    private static NormalizedRow normalize(GridRow g, Map<RosterField, Integer> columns) {
        String number = value(g, columns, RosterField.STUDENT_NUMBER);
        String last = value(g, columns, RosterField.LAST_NAME);
        String first = value(g, columns, RosterField.FIRST_NAME);
        String middle = value(g, columns, RosterField.MIDDLE_NAME);
        String email = value(g, columns, RosterField.EMAIL);
        String program = value(g, columns, RosterField.PROGRAM);
        String section = value(g, columns, RosterField.SECTION);
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

    private static String value(GridRow g, Map<RosterField, Integer> columns, RosterField f) {
        Integer idx = columns.get(f);
        if (idx == null || idx >= g.cells().size()) return null;
        String v = Normalizer.normalize(g.cells().get(idx), Normalizer.Form.NFC).strip().replaceAll("\\s+", " ");
        return v.isEmpty() ? null : v;
    }

    private static String stored(String v) {
        return v == null ? null : (v.length() > MAX_STORED_CHARS ? v.substring(0, MAX_STORED_CHARS) : v);
    }

    private static ApiException bad(ErrorCode code, String reason) {
        return ApiException.badRequest(code, reason);
    }
}
