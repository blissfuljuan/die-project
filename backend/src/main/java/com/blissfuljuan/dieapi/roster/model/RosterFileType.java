package com.blissfuljuan.dieapi.roster.model;

import java.util.Locale;

/** Roster upload formats, decided by file extension (the reader then also checks the content). */
public enum RosterFileType {
    CSV(".csv"), XLSX(".xlsx");

    private final String extension;

    RosterFileType(String extension) {
        this.extension = extension;
    }

    /** The type for a file name, or null when the extension is not supported. */
    public static RosterFileType fromFileName(String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        for (RosterFileType t : values()) {
            if (name.endsWith(t.extension)) return t;
        }
        return null;
    }
}
