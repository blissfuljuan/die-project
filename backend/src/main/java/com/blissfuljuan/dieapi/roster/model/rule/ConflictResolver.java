package com.blissfuljuan.dieapi.roster.model.rule;

import com.blissfuljuan.dieapi.roster.model.CommitEffect;
import com.blissfuljuan.dieapi.roster.model.ConflictKind;
import com.blissfuljuan.dieapi.roster.model.ResolutionAction;

import java.util.List;

/** Strategy for one kind of roster conflict: which decisions are valid and what each does at commit. */
public interface ConflictResolver {

    ConflictKind kind();

    /** Resolutions an instructor may choose, in presentation order. */
    List<ResolutionAction> allowed();

    /** What commit does for a chosen resolution; throws IllegalStateException for one that is not allowed. */
    CommitEffect effectOf(ResolutionAction resolution);
}
