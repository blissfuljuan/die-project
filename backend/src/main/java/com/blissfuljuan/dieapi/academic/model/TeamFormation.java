package com.blissfuljuan.dieapi.academic.model;

import com.blissfuljuan.dieapi.common.util.Enums;

/** Who forms the teams of a class (persisted by name in academic_class.team_formation). */
public enum TeamFormation {
    PARTICIPANT_FORMED, COORDINATOR_ASSIGNED;

    /** Exact lookup by name, or null. */
    public static TeamFormation parse(String text) {
        return Enums.parse(TeamFormation.class, text);
    }
}
