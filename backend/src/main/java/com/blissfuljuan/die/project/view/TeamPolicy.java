package com.blissfuljuan.die.project.view;

/** Team rules of the class a project belongs to (what students may and may not do). */
public record TeamPolicy(String formation, int minSize, int maxSize, boolean studentsMayChangeTeams) {}
