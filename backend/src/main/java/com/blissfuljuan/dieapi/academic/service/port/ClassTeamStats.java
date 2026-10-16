package com.blissfuljuan.dieapi.academic.service.port;

import java.util.UUID;

/**
 * Port: facts about the teams of a class that class policy changes must respect. Implemented by the project
 * module; academic only asks, so it does not depend on projects.
 */
public interface ClassTeamStats {

    /** Number of members of the largest active team in the class (0 if there is none). */
    int largestActiveTeamSize(UUID classId);
}
