package com.blissfuljuan.dieapi.project.model;

import java.util.List;
import java.util.UUID;

/**
 * Ordered membership changes: apply removals, then demotions, then promotions, then additions.
 * That order keeps "exactly one representative" valid after every step.
 */
public record MembershipPlan(List<UUID> removals, List<UUID> demotions, List<UUID> promotions, List<UUID> additions,
                             UUID representative) {

    /** Role a newly added member gets. */
    public TeamRole roleOfAddition(UUID userId) {
        return userId.equals(representative) ? TeamRole.REPRESENTATIVE : TeamRole.MEMBER;
    }
}
