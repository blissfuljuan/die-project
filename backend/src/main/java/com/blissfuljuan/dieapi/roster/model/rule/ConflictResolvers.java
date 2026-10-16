package com.blissfuljuan.dieapi.roster.model.rule;

import com.blissfuljuan.dieapi.roster.model.ConflictKind;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;

/** Registry of one {@link ConflictResolver} per {@link ConflictKind}; refuses to be built if a kind has none. */
public final class ConflictResolvers {

    private final Map<ConflictKind, ConflictResolver> byKind = new EnumMap<>(ConflictKind.class);

    public ConflictResolvers(Collection<? extends ConflictResolver> resolvers) {
        for (ConflictResolver r : resolvers) {
            if (byKind.put(r.kind(), r) != null) {
                throw new IllegalStateException("Two conflict resolvers for " + r.kind());
            }
        }
        for (ConflictKind kind : ConflictKind.values()) {
            if (!byKind.containsKey(kind)) throw new IllegalStateException("No conflict resolver for " + kind);
        }
    }

    public ConflictResolver forKind(ConflictKind kind) {
        return byKind.get(kind);
    }
}
