package com.blissfuljuan.dieapi.academic.model;

import java.util.UUID;

/** Command to enroll one student, identified by exactly one of studentId / studentNumber. */
public record EnrollStudent(UUID studentId, String studentNumber, String lastName, String program, String section) {}
