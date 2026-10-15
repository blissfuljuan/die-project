package com.blissfuljuan.die.roster.model;

/** An instructor's decision for one conflicting row of an import preview. */
public record ResolutionChange(int rowNumber, String resolution) {}
