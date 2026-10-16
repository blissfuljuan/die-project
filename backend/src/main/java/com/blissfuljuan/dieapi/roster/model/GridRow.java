package com.blissfuljuan.dieapi.roster.model;

import java.util.List;

/** One raw row read from an uploaded file, before header mapping and validation. */
public record GridRow(int rowNumber, List<String> cells, boolean hasFormula) {}
