package com.blissfuljuan.dieapi.academic.model;

import java.util.List;
import java.util.UUID;

/** Command: assign a coordinator account to a workspace; null capabilities means the defaults. */
public record AddCoordinator(UUID userId, List<String> capabilities) {}
