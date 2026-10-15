package com.blissfuljuan.dieapi.auth.view;

import java.util.UUID;

/** The signed-in user as the client may see it. */
public record MeResponse(UUID id, String email, String firstName, String lastName, String role) {}
