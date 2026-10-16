package com.blissfuljuan.dieapi.roster.model.rule;

import com.blissfuljuan.dieapi.common.exception.ApiException;
import com.blissfuljuan.dieapi.roster.model.GridRow;
import com.blissfuljuan.dieapi.roster.model.NormalizedRow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RosterRowValidatorTest {

    private final RosterRowValidator validator = new RosterRowValidator(3);

    private static GridRow row(int n, String... cells) {
        return new GridRow(n, List.of(cells), false);
    }

    private static GridRow header() {
        return row(1, "Student ID", "Last Name", "First Name", "Email");
    }

    private static String codeOf(List<GridRow> grid) {
        return ((ApiException) catchThrowable(grid)).getCode();
    }

    private static Throwable catchThrowable(List<GridRow> grid) {
        try {
            new RosterRowValidator(3).validate(grid);
        } catch (Throwable t) {
            return t;
        }
        throw new AssertionError("expected a failure");
    }

    @Test
    void normalizesValidRowsAndLowerCasesEmail() {
        List<NormalizedRow> rows = validator.validate(List.of(header(),
            row(2, " 2024-001 ", "dela   Cruz", "Juan", "Juan@Example.COM")));
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.rowNumber()).isEqualTo(2);
            assertThat(r.studentNumber()).isEqualTo("2024-001");
            assertThat(r.lastName()).isEqualTo("dela Cruz");
            assertThat(r.email()).isEqualTo("juan@example.com");
            assertThat(r.invalidReason()).isNull();
        });
    }

    @Test
    void headerMatchingIgnoresCaseAndPunctuation() {
        List<NormalizedRow> rows = validator.validate(List.of(
            row(1, "student_no", "SURNAME", "Given-Name"), row(2, "1", "A", "B")));
        assertThat(rows.get(0).invalidReason()).isNull();
    }

    @Test
    void leadingBlankRowsAreSkippedBeforeTheHeader() {
        List<NormalizedRow> rows = validator.validate(List.of(row(1, "", ""), header(), row(3, "1", "A", "B", "")));
        assertThat(rows).hasSize(1);
    }

    @Test
    void formulaRowsAreInvalidEvenWhenTheirCellsAreBlank() {
        List<NormalizedRow> rows = validator.validate(List.of(header(), new GridRow(2, List.of("", "", "", ""), true)));
        assertThat(rows.get(0).invalidReason()).isEqualTo("Spreadsheet formulas are not allowed");
    }

    @Test
    void injectionPrefixesAndControlCharactersAreInvalid() {
        for (String bad : List.of("=1+1", "+5", "@x", "-abc","a\u0007b")) {
            List<NormalizedRow> rows = validator.validate(List.of(header(), row(2, "1", bad, "B", "")));
            assertThat(rows.get(0).invalidReason()).as(bad).startsWith("A cell starts with");
        }
    }

    @Test
    void minusFollowedByNumberIsAllowed() {
        List<NormalizedRow> rows = validator.validate(List.of(header(), row(2, "-12", "A", "B", "")));
        assertThat(rows.get(0).invalidReason()).isNull();
    }

    @Test
    void requiredFieldsLengthsAndEmailAreChecked() {
        assertThat(validator.validate(List.of(header(), row(2, "", "A", "B", ""))).get(0).invalidReason())
            .isEqualTo("Student ID is required");
        assertThat(validator.validate(List.of(header(), row(2, "1", "", "B", ""))).get(0).invalidReason())
            .isEqualTo("Last name is required");
        assertThat(validator.validate(List.of(header(), row(2, "1", "A", "", ""))).get(0).invalidReason())
            .isEqualTo("First name is required");
        assertThat(validator.validate(List.of(header(), row(2, "1".repeat(65), "A", "B", ""))).get(0).invalidReason())
            .isEqualTo("Student ID is too long");
        assertThat(validator.validate(List.of(header(), row(2, "1", "A".repeat(101), "B", ""))).get(0).invalidReason())
            .isEqualTo("A value is longer than 100 characters");
        assertThat(validator.validate(List.of(header(), row(2, "1", "A", "B", "not-an-email"))).get(0).invalidReason())
            .isEqualTo("Email address is not valid");
    }

    @Test
    void blankRowsAreSkippedAndDoNotCountTowardTheCap() {
        List<GridRow> grid = new ArrayList<>(List.of(header()));
        for (int i = 0; i < 5; i++) grid.add(row(2 + i, "", "", "", ""));
        grid.add(row(10, "1", "A", "B", ""));
        assertThat(validator.validate(grid)).hasSize(1);
    }

    @Test
    void fileLevelProblemsThrowSpecificCodes() {
        assertThat(codeOf(List.of(row(1, "", "")))).isEqualTo("HEADER_ROW_MISSING");
        assertThat(codeOf(List.of(row(1, "Student ID", "Last Name")))).isEqualTo("MISSING_COLUMNS");
        assertThat(codeOf(List.of(row(1, "Student ID", "ID", "Last Name", "First Name")))).isEqualTo("DUPLICATE_COLUMN");
        assertThat(codeOf(List.of(header()))).isEqualTo("NO_DATA_ROWS");
        List<GridRow> tooMany = new ArrayList<>(List.of(header()));
        for (int i = 0; i < 4; i++) tooMany.add(row(2 + i, "1" + i, "A", "B", ""));
        assertThat(codeOf(tooMany)).isEqualTo("TOO_MANY_ROWS");
    }

    @Test
    void missingColumnsMessageNamesThem() {
        assertThatThrownBy(() -> validator.validate(List.of(row(1, "Student ID"))))
            .hasMessageContaining("Missing required column(s): Last Name, First Name");
    }
}
