package com.blissfuljuan.dieapi.access.model;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/**
 * Resource-level capabilities a coordinator can hold in one workspace. The Owner implicitly holds all of them.
 * Lives in {@code access.model} because the policy layer decides on them; the academic module only stores/lists them.
 * <p>
 * {@code administrative} capabilities cover operations an administrator may also perform (administration of
 * settings, roster, projects, coordinators); the others are content-related and reserved for later slices
 * (stored but not yet enforced anywhere): administrators never pass a check for them.
 */
public enum WorkspaceCapability {
    MANAGE_SETTINGS(true),
    MANAGE_ROSTER(true),
    MANAGE_PROJECTS(true),
    MANAGE_COORDINATORS(true),
    // reserved for later slices
    CONFIGURE_REQUIREMENTS(false),
    CONFIGURE_WORKFLOW(false),
    MANAGE_TEMPLATES(false),
    MANAGE_STORAGE(false),
    INITIATE_EVALUATION(false),
    REVIEW(false),
    APPROVE(false),
    PUBLISH(false);

    /** Granted to a new coordinator when the request names none. */
    public static final Set<WorkspaceCapability> DEFAULTS = Set.copyOf(EnumSet.of(MANAGE_ROSTER, MANAGE_PROJECTS));

    private final boolean administrative;

    WorkspaceCapability(boolean administrative) {
        this.administrative = administrative;
    }

    public boolean administrative() {
        return administrative;
    }

    /** Null for an unknown name. */
    public static WorkspaceCapability parse(String value) {
        if (value == null) return null;
        return Arrays.stream(values()).filter(c -> c.name().equals(value.trim())).findFirst().orElse(null);
    }

    public static Set<WorkspaceCapability> all() {
        return EnumSet.allOf(WorkspaceCapability.class);
    }

    public static Set<WorkspaceCapability> administrativeOnes() {
        EnumSet<WorkspaceCapability> set = EnumSet.noneOf(WorkspaceCapability.class);
        for (WorkspaceCapability c : values()) if (c.administrative) set.add(c);
        return set;
    }
}
