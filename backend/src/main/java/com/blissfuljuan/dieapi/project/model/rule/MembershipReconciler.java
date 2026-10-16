package com.blissfuljuan.dieapi.project.model.rule;

import com.blissfuljuan.dieapi.common.exception.ApiException;
import com.blissfuljuan.dieapi.common.exception.ErrorCode;
import com.blissfuljuan.dieapi.project.model.MembershipPlan;
import com.blissfuljuan.dieapi.project.model.TeamRole;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Pure rules for turning "current members" into "desired members": who is the representative, and what changes. */
public class MembershipReconciler {

    /**
     * The representative of a team after the change: the requested one (must be a desired member), else the
     * current representative if still a member, else the first desired member.
     */
    public UUID chooseRepresentative(UUID requested, Map<UUID, TeamRole> current, List<UUID> desired) {
        if (requested != null) {
            if (!desired.contains(requested)) {
                throw ApiException.badRequest(ErrorCode.INVALID_REPRESENTATIVE, "representativeId must be one of memberIds");
            }
            return requested;
        }
        return current.entrySet().stream()
            .filter(e -> e.getValue() == TeamRole.REPRESENTATIVE && desired.contains(e.getKey()))
            .map(Map.Entry::getKey).findFirst().orElse(desired.get(0));
    }

    /** {@code representative} is null for individual projects (everyone is a plain member). */
    public MembershipPlan plan(Map<UUID, TeamRole> current, List<UUID> desired, UUID representative) {
        List<UUID> removals = new ArrayList<>();
        for (UUID existing : current.keySet()) {
            if (!desired.contains(existing)) removals.add(existing);
        }
        List<UUID> demotions = new ArrayList<>();
        List<UUID> promotions = new ArrayList<>();
        List<UUID> additions = new ArrayList<>();
        for (UUID id : desired) {
            TeamRole wanted = id.equals(representative) ? TeamRole.REPRESENTATIVE : TeamRole.MEMBER;
            TeamRole has = current.get(id);
            if (has == null) additions.add(id);
            else if (has != wanted) {
                if (wanted == TeamRole.MEMBER) demotions.add(id);
                else promotions.add(id);
            }
        }
        return new MembershipPlan(removals, demotions, promotions, additions, representative);
    }
}
