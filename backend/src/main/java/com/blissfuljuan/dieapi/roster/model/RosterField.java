package com.blissfuljuan.dieapi.roster.model;

/** The roster columns the importer understands. */
public enum RosterField {
    STUDENT_NUMBER("Student ID"), LAST_NAME("Last Name"), FIRST_NAME("First Name"), MIDDLE_NAME("Middle Name"),
    EMAIL("Email"), PROGRAM("Program"), SECTION("Section");

    private final String label;

    RosterField(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
