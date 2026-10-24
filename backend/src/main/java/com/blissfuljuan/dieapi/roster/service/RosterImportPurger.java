package com.blissfuljuan.dieapi.roster.service;

import com.blissfuljuan.dieapi.roster.repository.RosterImportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Deletes roster imports that were never committed (still PREVIEW, or CANCELLED) after a retention period. Their rows hold
 * names, student numbers and emails from an uploaded file that never became accounts, so they must not linger. COMMITTED
 * imports stay: they are the record of how a roster was built.
 */
@Service
public class RosterImportPurger {

    private static final Logger log = LoggerFactory.getLogger(RosterImportPurger.class);

    private final RosterImportRepository imports;
    private final int retentionDays;

    public RosterImportPurger(RosterImportRepository imports, @Value("${die.roster.preview-retention-days:7}") int retentionDays) {
        if (retentionDays < 1) {
            throw new IllegalArgumentException("die.roster.preview-retention-days must be at least 1");
        }
        this.imports = imports;
        this.retentionDays = retentionDays;
    }

    /** One pass; returns the number of imports removed (their rows go with them). */
    public int purgeOnce() {
        int removed = imports.deleteUncommittedOlderThanDays(retentionDays);
        if (removed > 0) {
            log.info("purged {} uncommitted roster imports older than {} days", removed, retentionDays);
        }
        return removed;
    }
}
