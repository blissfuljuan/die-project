package com.blissfuljuan.dieapi.project.model.rule;

import com.blissfuljuan.dieapi.common.exception.ApiException;
import com.blissfuljuan.dieapi.project.model.MembershipPlan;
import com.blissfuljuan.dieapi.project.model.TeamRole;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MembershipReconcilerTest {

    private final MembershipReconciler reconciler = new MembershipReconciler();
    private final UUID a = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private final UUID b = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private final UUID c = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private final UUID d = UUID.fromString("00000000-0000-0000-0000-00000000000d");

    private static Map<UUID, TeamRole> current(Object... pairs) {
        Map<UUID, TeamRole> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((UUID) pairs[i], (TeamRole) pairs[i + 1]);
        return m;
    }

    @Test
    void requestedRepresentativeWinsWhenAmongDesired() {
        var cur = current(a, TeamRole.REPRESENTATIVE, b, TeamRole.MEMBER);
        assertThat(reconciler.chooseRepresentative(b, cur, List.of(a, b))).isEqualTo(b);
    }

    @Test
    void requestedRepresentativeOutsideDesiredIsRejected() {
        var cur = current(a, TeamRole.REPRESENTATIVE);
        assertThatThrownBy(() -> reconciler.chooseRepresentative(d, cur, List.of(a, b)))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("INVALID_REPRESENTATIVE"));
    }

    @Test
    void currentRepresentativeIsKeptWhenStillDesired() {
        var cur = current(a, TeamRole.MEMBER, b, TeamRole.REPRESENTATIVE);
        assertThat(reconciler.chooseRepresentative(null, cur, List.of(a, b, c))).isEqualTo(b);
    }

    @Test
    void firstDesiredBecomesRepresentativeWhenCurrentOneIsRemoved() {
        var cur = current(a, TeamRole.REPRESENTATIVE, b, TeamRole.MEMBER);
        assertThat(reconciler.chooseRepresentative(null, cur, List.of(c, b))).isEqualTo(c);
    }

    @Test
    void identicalMembershipIsANoOp() {
        var cur = current(a, TeamRole.REPRESENTATIVE, b, TeamRole.MEMBER);
        MembershipPlan plan = reconciler.plan(cur, List.of(a, b), a);
        assertThat(plan.removals()).isEmpty();
        assertThat(plan.demotions()).isEmpty();
        assertThat(plan.promotions()).isEmpty();
        assertThat(plan.additions()).isEmpty();
    }

    @Test
    void handoverDemotesTheOldAndPromotesTheNewRepresentative() {
        var cur = current(a, TeamRole.REPRESENTATIVE, b, TeamRole.MEMBER);
        MembershipPlan plan = reconciler.plan(cur, List.of(a, b), b);
        assertThat(plan.demotions()).containsExactly(a);
        assertThat(plan.promotions()).containsExactly(b);
        assertThat(plan.removals()).isEmpty();
        assertThat(plan.additions()).isEmpty();
    }

    @Test
    void removalsKeepTheCurrentOrderAndAdditionsKeepTheDesiredOrder() {
        var cur = current(a, TeamRole.REPRESENTATIVE, b, TeamRole.MEMBER, c, TeamRole.MEMBER);
        MembershipPlan plan = reconciler.plan(cur, List.of(d, b, a), a);
        assertThat(plan.removals()).containsExactly(c);
        assertThat(plan.additions()).containsExactly(d);
        assertThat(plan.roleOfAddition(d)).isEqualTo(TeamRole.MEMBER);
    }

    @Test
    void newRepresentativeAddedInOneStepGetsTheRepresentativeRole() {
        var cur = current(a, TeamRole.REPRESENTATIVE);
        MembershipPlan plan = reconciler.plan(cur, List.of(d), d);
        assertThat(plan.removals()).containsExactly(a);
        assertThat(plan.additions()).containsExactly(d);
        assertThat(plan.roleOfAddition(d)).isEqualTo(TeamRole.REPRESENTATIVE);
        assertThat(plan.demotions()).isEmpty();
    }

    @Test
    void individualProjectsHaveNoRepresentative() {
        var cur = current(a, TeamRole.MEMBER);
        MembershipPlan plan = reconciler.plan(cur, List.of(b), null);
        assertThat(plan.removals()).containsExactly(a);
        assertThat(plan.additions()).containsExactly(b);
        assertThat(plan.roleOfAddition(b)).isEqualTo(TeamRole.MEMBER);
        assertThat(plan.promotions()).isEmpty();
    }
}
