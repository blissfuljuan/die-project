package com.blissfuljuan.dieapi.academic.model;

import java.util.UUID;

/** The few account facts enrollment decisions need. */
public record StudentRef(UUID id, String role, String status, String lastName, UUID createdBy) {}
