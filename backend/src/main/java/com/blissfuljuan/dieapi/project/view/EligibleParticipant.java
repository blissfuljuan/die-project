package com.blissfuljuan.dieapi.project.view;

import java.util.UUID;

/** A classmate a student may invite: name only, never a student number or email. */
public record EligibleParticipant(UUID id, String firstName, String lastName) {}
