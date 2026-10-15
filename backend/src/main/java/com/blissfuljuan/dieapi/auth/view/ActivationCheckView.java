package com.blissfuljuan.dieapi.auth.view;

/** What the activation form may show once the temporary credential has been proven. */
public record ActivationCheckView(String firstName, String lastName, String email, boolean emailVerified) {}
