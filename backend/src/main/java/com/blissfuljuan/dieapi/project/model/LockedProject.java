package com.blissfuljuan.dieapi.project.model;

import com.blissfuljuan.dieapi.academic.model.LockedClass;

/** A project and its class, both read while their row locks are held. */
public record LockedProject(ProjectRow project, LockedClass cls) {}
