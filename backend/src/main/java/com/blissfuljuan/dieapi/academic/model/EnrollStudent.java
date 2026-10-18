package com.blissfuljuan.dieapi.academic.model;

import java.util.UUID;

/** Command to enroll one student, identified by exactly one of participantId / studentNumber. */
public record EnrollStudent(UUID participantId, String studentNumber, String lastName, String program, String section) {}
