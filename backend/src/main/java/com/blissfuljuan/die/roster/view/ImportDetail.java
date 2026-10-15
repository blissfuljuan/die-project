package com.blissfuljuan.die.roster.view;

import java.util.List;

public record ImportDetail(ImportView importInfo, List<RowView> rows, int page, int size, long totalRows) {}
