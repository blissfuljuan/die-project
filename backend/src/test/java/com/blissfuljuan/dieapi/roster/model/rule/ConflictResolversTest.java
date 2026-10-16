package com.blissfuljuan.dieapi.roster.model.rule;

import com.blissfuljuan.dieapi.roster.model.CommitEffect;
import com.blissfuljuan.dieapi.roster.model.ConflictKind;
import com.blissfuljuan.dieapi.roster.model.ResolutionAction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConflictResolversTest {

    private static List<ConflictResolver> standard() {
        List<ConflictResolver> all = new ArrayList<>(List.of(new NameMismatchConflict()));
        for (ConflictKind k : ConflictKind.values()) {
            if (k != ConflictKind.ID_NAME_MISMATCH) all.add(new SkipOnlyConflict(k));
        }
        return all;
    }

    @Test
    void nameMismatchAllowsUsingTheExistingStudentOrSkipping() {
        ConflictResolver r = new ConflictResolvers(standard()).forKind(ConflictKind.ID_NAME_MISMATCH);
        assertThat(r.allowed()).containsExactly(ResolutionAction.USE_EXISTING, ResolutionAction.SKIP);
        assertThat(r.effectOf(ResolutionAction.USE_EXISTING)).isEqualTo(CommitEffect.ENROLL_EXISTING);
        assertThat(r.effectOf(ResolutionAction.SKIP)).isEqualTo(CommitEffect.SKIP);
        assertThatThrownBy(() -> r.effectOf(ResolutionAction.CREATE_NEW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void everyOtherKindCanOnlyBeSkipped() {
        ConflictResolvers resolvers = new ConflictResolvers(standard());
        for (ConflictKind k : ConflictKind.values()) {
            if (k == ConflictKind.ID_NAME_MISMATCH) continue;
            ConflictResolver r = resolvers.forKind(k);
            assertThat(r.allowed()).as(k.name()).containsExactly(ResolutionAction.SKIP);
            assertThat(r.effectOf(ResolutionAction.SKIP)).isEqualTo(CommitEffect.SKIP);
            assertThatThrownBy(() -> r.effectOf(ResolutionAction.USE_EXISTING)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void registryRefusesToBuildWhenAKindHasNoResolver() {
        List<ConflictResolver> missingOne = standard();
        missingOne.removeIf(r -> r.kind() == ConflictKind.ID_UNAVAILABLE);
        assertThatThrownBy(() -> new ConflictResolvers(missingOne))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("ID_UNAVAILABLE");
    }

    @Test
    void registryRefusesTwoResolversForOneKind() {
        List<ConflictResolver> twice = standard();
        twice.add(new SkipOnlyConflict(ConflictKind.ID_UNAVAILABLE));
        assertThatThrownBy(() -> new ConflictResolvers(twice)).isInstanceOf(IllegalStateException.class);
    }
}
