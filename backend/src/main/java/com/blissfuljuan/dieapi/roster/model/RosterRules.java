package com.blissfuljuan.dieapi.roster.model;

import java.util.List;

/** Row outcomes and resolutions of a roster import, and which resolutions each kind of conflict allows. */
public final class RosterRules {

    private RosterRules() {}

    public static final String CREATE = "CREATE";
    public static final String MATCH = "MATCH";
    public static final String SKIP_DUPLICATE = "SKIP_DUPLICATE";
    public static final String CONFLICT = "CONFLICT";
    public static final String INVALID = "INVALID";

    public static final String USE_EXISTING = "USE_EXISTING";
    public static final String SKIP = "SKIP";
    public static final String CREATE_NEW = "CREATE_NEW";

    public static List<String> allowedResolutions(String conflictKind) {
        if (conflictKind == null) return List.of();
        return switch (conflictKind) {
            case "ID_NAME_MISMATCH" -> List.of(USE_EXISTING, SKIP);   // only for students the caller already teaches
            default -> List.of(SKIP);
        };
    }
}
