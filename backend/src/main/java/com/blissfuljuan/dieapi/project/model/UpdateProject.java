package com.blissfuljuan.dieapi.project.model;

/** Command to change a project's details; null fields stay as they are. */
public record UpdateProject(Long version, String title, String description, String teamName) {}
