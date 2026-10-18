package com.blissfuljuan.dieapi.common.view;

import java.time.Instant;

/** Public application status (P0-VS01). Deliberately minimal: no hosts, versions of dependencies or counts. */
public record StatusView(String application, String apiVersion, String buildVersion, String status, Instant time) {}
