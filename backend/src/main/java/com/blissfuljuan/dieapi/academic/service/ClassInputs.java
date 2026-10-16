package com.blissfuljuan.dieapi.academic.service;

import com.blissfuljuan.dieapi.common.exception.ApiException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/** Small input-normalisation helpers shared by the class services. */
final class ClassInputs {

    private ClassInputs() {}

    static List<UUID> distinct(List<UUID> ids) {
        return ids == null ? new ArrayList<>() : new ArrayList<>(new LinkedHashSet<>(ids));
    }

    static String pick(String requested, String current, String field) {
        return requested == null ? current : required(requested, field);
    }

    static String required(String value, String field) {
        String v = blankToNull(value);
        if (v == null) throw ApiException.badRequest("FIELD_REQUIRED", field + " is required");
        if (v.length() > 100) throw ApiException.badRequest("FIELD_TOO_LONG", field + " is too long");
        return v;
    }

    static boolean sameText(String a, String b) {
        return a != null && b != null && a.strip().equalsIgnoreCase(b.strip());
    }

    static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
