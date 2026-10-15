package com.blissfuljuan.dieapi.account.model;

/** Command: create an account (administrators only). */
public record CreateUser(String role, String studentNumber, String lastName, String firstName,
                         String middleName, String email) {}
