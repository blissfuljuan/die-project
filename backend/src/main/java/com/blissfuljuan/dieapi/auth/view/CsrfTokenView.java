package com.blissfuljuan.dieapi.auth.view;

/** Where to send the CSRF token (header name) and its value; the same token is also set as the XSRF-TOKEN cookie. */
public record CsrfTokenView(String headerName, String token) {}
