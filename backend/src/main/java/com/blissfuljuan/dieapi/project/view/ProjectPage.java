package com.blissfuljuan.dieapi.project.view;

import java.util.List;

/** One page of a workspace's projects, already restricted to what the caller may see. */
public record ProjectPage(List<ProjectView> items, int page, int size, long total) {}
