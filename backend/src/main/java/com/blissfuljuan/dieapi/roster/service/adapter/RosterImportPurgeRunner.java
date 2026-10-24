package com.blissfuljuan.dieapi.roster.service.adapter;

import com.blissfuljuan.dieapi.common.util.PeriodicTask;
import com.blissfuljuan.dieapi.roster.service.RosterImportPurger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Runs {@link RosterImportPurger#purgeOnce()} periodically. Disable with die.roster.purge.enabled=false (tests call it by hand). */
@Component
@ConditionalOnProperty(name = "die.roster.purge.enabled", havingValue = "true", matchIfMissing = true)
public class RosterImportPurgeRunner extends PeriodicTask {

    public RosterImportPurgeRunner(RosterImportPurger purger, @Value("${die.roster.purge.interval-minutes:360}") long intervalMinutes) {
        super("roster-import-purge", 120, intervalMinutes * 60, purger::purgeOnce);
    }
}
