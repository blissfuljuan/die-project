package com.blissfuljuan.dieapi.audit.model;

import java.util.Locale;

/** Kind of resource an audit row is about. {@link #value()} is the stored {@code resource_type} string. */
public enum AuditResource {
    CLASS, USER, PROJECT, AUTH;

    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static AuditResource fromValue(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
