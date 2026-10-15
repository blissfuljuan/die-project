package com.blissfuljuan.die.roster.view;

import java.util.List;

/** Deliberately has no account ids: the client works with row numbers only. */
public record RowView(int rowNumber, String studentNumber, String lastName, String firstName,
                      String middleName, String email, String program, String section, String action,
                      String conflictKind, String resolution, String message,
                      List<String> allowedResolutions) {}
