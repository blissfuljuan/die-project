package com.blissfuljuan.dieapi.access.model;

import java.util.Map;

/**
 * SQL fragment (a JOIN) and its parameters restricting a query to what the caller may see. Produced by the
 * access policy, applied by repositories: authorization is decided in one place, filtering happens in SQL.
 */
public record SqlScope(String join, Map<String, Object> params) {}
