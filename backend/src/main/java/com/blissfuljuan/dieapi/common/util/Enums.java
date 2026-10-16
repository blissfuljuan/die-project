package com.blissfuljuan.dieapi.common.util;

/** Small helpers for the typed domain values (enums persisted by name). */
public final class Enums {

    private Enums() {}

    /** Exact (case-sensitive) lookup by constant name; {@code null} when the text is null or not a constant. */
    public static <E extends Enum<E>> E parse(Class<E> type, String text) {
        if (text == null) return null;
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(text)) return constant;
        }
        return null;
    }
}
