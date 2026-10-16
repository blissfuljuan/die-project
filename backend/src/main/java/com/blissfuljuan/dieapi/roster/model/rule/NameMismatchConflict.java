package com.blissfuljuan.dieapi.roster.model.rule;

import com.blissfuljuan.dieapi.roster.model.CommitEffect;
import com.blissfuljuan.dieapi.roster.model.ConflictKind;
import com.blissfuljuan.dieapi.roster.model.ResolutionAction;

import java.util.List;

/** The Student ID belongs to a student the caller already teaches, under a different name: use that student or skip. */
public final class NameMismatchConflict implements ConflictResolver {

    @Override
    public ConflictKind kind() {
        return ConflictKind.ID_NAME_MISMATCH;
    }

    @Override
    public List<ResolutionAction> allowed() {
        return List.of(ResolutionAction.USE_EXISTING, ResolutionAction.SKIP);
    }

    @Override
    public CommitEffect effectOf(ResolutionAction resolution) {
        return switch (resolution) {
            case USE_EXISTING -> CommitEffect.ENROLL_EXISTING;
            case SKIP -> CommitEffect.SKIP;
            default -> throw new IllegalStateException("Unexpected resolution " + resolution);
        };
    }
}
