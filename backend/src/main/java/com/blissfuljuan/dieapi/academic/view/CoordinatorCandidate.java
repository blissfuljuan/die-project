package com.blissfuljuan.dieapi.academic.view;

import java.util.UUID;

/** A coordinator account that could be added to a workspace (found by name or email). */
public record CoordinatorCandidate(UUID userId, String firstName, String lastName, String email) {}
