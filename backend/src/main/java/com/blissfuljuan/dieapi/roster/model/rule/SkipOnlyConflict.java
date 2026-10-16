package com.blissfuljuan.dieapi.roster.model.rule;

import com.blissfuljuan.dieapi.roster.model.CommitEffect;
import com.blissfuljuan.dieapi.roster.model.ConflictKind;
import com.blissfuljuan.dieapi.roster.model.ResolutionAction;

import java.util.List;

/** A conflict that can only be skipped (the Student ID cannot be used here, or the file contradicts itself). */
public final class SkipOnlyConflict implements ConflictResolver {

    private final ConflictKind kind;

    public SkipOnlyConflict(ConflictKind kind) {
        this.kind = kind;
    }

    @Override
    public ConflictKind kind() {
        return kind;
    }

    @Override
    public List<ResolutionAction> allowed() {
        return List.of(ResolutionAction.SKIP);
    }

    @Override
    public CommitEffect effectOf(ResolutionAction resolution) {
        if (resolution == ResolutionAction.SKIP) return CommitEffect.SKIP;
        throw new IllegalStateException("Unexpected resolution " + resolution);
    }
}
