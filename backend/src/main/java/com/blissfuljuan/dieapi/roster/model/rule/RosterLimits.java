package com.blissfuljuan.dieapi.roster.model.rule;

/** Size limits shared by the file readers and the row validator. */
public final class RosterLimits {

    private RosterLimits() {}

    public static final int MAX_COLUMNS = 30;
    public static final int MAX_CELL_CHARS = 500;
    public static final int MAX_STORED_CHARS = 200;

    /** Raw rows a reader may hand over (header and blank lines included) before the file is refused. */
    public static int rawRowLimit(int maxRows) {
        return maxRows * 4 + 10;
    }

    /** Null-safe, length-capped cell text. */
    public static String cap(String v) {
        return v == null ? "" : (v.length() > MAX_CELL_CHARS ? v.substring(0, MAX_CELL_CHARS) : v);
    }
}
