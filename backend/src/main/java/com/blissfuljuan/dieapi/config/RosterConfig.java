package com.blissfuljuan.dieapi.config;

import com.blissfuljuan.dieapi.roster.model.ConflictKind;
import com.blissfuljuan.dieapi.roster.model.rule.ConflictResolver;
import com.blissfuljuan.dieapi.roster.model.rule.ConflictResolvers;
import com.blissfuljuan.dieapi.roster.model.rule.NameMismatchConflict;
import com.blissfuljuan.dieapi.roster.model.rule.SkipOnlyConflict;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/** Wires the pure roster rules. Startup fails if a {@link ConflictKind} has no resolver. */
@Configuration
public class RosterConfig {

    @Bean
    ConflictResolvers conflictResolvers() {
        List<ConflictResolver> all = new ArrayList<>();
        all.add(new NameMismatchConflict());
        for (ConflictKind kind : ConflictKind.values()) {
            if (kind != ConflictKind.ID_NAME_MISMATCH) all.add(new SkipOnlyConflict(kind));
        }
        return new ConflictResolvers(all);
    }
}
