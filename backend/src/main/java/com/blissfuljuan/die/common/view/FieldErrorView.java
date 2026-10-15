package com.blissfuljuan.die.common.view;

/** One invalid request field. Never carries the rejected value (it may be a password or other secret). */
public record FieldErrorView(String field, String message) {}
