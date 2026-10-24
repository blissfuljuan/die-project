package com.blissfuljuan.dieapi.academic.view;

import java.util.List;

/** One page of the roster (staff only). */
public record EnrollmentPage(List<EnrollmentView> items, int page, int size, long total) {}
