package com.blissfuljuan.dieapi.roster.model;

import com.blissfuljuan.dieapi.common.util.Enums;

/** An instructor's decision for a conflicting row (persisted by name in roster_import_row.resolution). */
public enum ResolutionAction {
    USE_EXISTING, SKIP, CREATE_NEW;

    /** Exact lookup by name, or null (client input is untrusted, so unknown text is not an exception here). */
    public static ResolutionAction parse(String text) {
        return Enums.parse(ResolutionAction.class, text);
    }
}
