package com.blissfuljuan.dieapi.roster.model;

/** One cleaned data row of an uploaded roster. {@code invalidReason} is null for usable rows. */
public record NormalizedRow(int rowNumber, String studentNumber, String lastName, String firstName,
                            String middleName, String email, String program, String section,
                            String invalidReason) {}
