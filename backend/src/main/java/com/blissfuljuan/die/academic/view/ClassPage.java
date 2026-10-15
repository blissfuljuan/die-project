package com.blissfuljuan.die.academic.view;

import java.util.List;

public record ClassPage(List<ClassView> items, int page, int size, long total) {}
