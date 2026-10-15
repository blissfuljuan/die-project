package io.die.evaluator.roster;

import static io.die.evaluator.roster.RosterModels.CONFLICT;
import static io.die.evaluator.roster.RosterModels.CREATE;
import static io.die.evaluator.roster.RosterModels.INVALID;
import static io.die.evaluator.roster.RosterModels.MATCH;
import static io.die.evaluator.roster.RosterModels.SKIP_DUPLICATE;

import io.die.evaluator.academic.EnrollmentRules;
import io.die.evaluator.auth.AppUserDetails;
import io.die.evaluator.roster.RosterImportRepository.ExistingUser;
import io.die.evaluator.roster.RosterModels.StoredRow;
import io.die.evaluator.roster.RosterParser.NormalizedRow;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Decides what each roster row means against the current database and the rows before it, FOR A GIVEN
 * CALLER. Deterministic for a given (file, database state, caller): commit runs it again and refuses to
 * proceed if the outcome of any row changed since the preview.
 *
 * Disclosure rules (the roster is typed by one instructor, the accounts belong to the whole school):
 *  - Messages never contain another account's name, email or id.
 *  - Student IDs that the caller cannot use — deactivated, not a student, a never-activated account the
 *    caller may not claim, or an account under a different name that the caller does not teach — all give the
 *    same ID_UNAVAILABLE conflict, which can only be skipped.
 *  - Only a student the caller already teaches can produce ID_NAME_MISMATCH (resolvable with USE_EXISTING).
 *  - Emails in the file are validated but never applied to accounts, so a roster cannot probe which
 *    addresses are registered or reserve an address; students supply and verify their own at activation.
 * Residual, accepted: a row whose ID *and* exact name match an ACTIVE account of another class is reported
 * as MATCH rather than CREATE.
 */
@Component
public class RosterAnalyzer {

    static final String UNAVAILABLE_MESSAGE = "This Student ID cannot be imported here; contact an administrator";

    private final RosterImportRepository repo;

    public RosterAnalyzer(RosterImportRepository repo) {
        this.repo = repo;
    }

    public List<StoredRow> classify(UUID classId, List<NormalizedRow> rows, AppUserDetails caller) {
        boolean admin = "ADMIN".equals(caller.getRole());
        Set<String> numbers = new HashSet<>();
        for (NormalizedRow r : rows) {
            if (r.invalidReason() == null) numbers.add(lower(r.studentNumber()));
        }
        Map<String, ExistingUser> existingByNumber = repo.usersByStudentNumber(numbers);
        Set<UUID> known = admin ? Set.of()
            : repo.studentsKnownToInstructor(caller.getId(), existingByNumber.values().stream().map(ExistingUser::id).toList());
        Map<UUID, String> enrollments = repo.enrollmentStatuses(classId);

        Map<String, NormalizedRow> firstByNumber = new HashMap<>();
        List<StoredRow> out = new ArrayList<>(rows.size());

        for (NormalizedRow r : rows) {
            if (r.invalidReason() != null) {
                out.add(stored(r, INVALID, null, r.invalidReason(), null));
                continue;
            }
            String key = lower(r.studentNumber());
            NormalizedRow prior = firstByNumber.get(key);
            if (prior != null) {
                if (sameName(prior.lastName(), prior.firstName(), r.lastName(), r.firstName())) {
                    out.add(stored(r, SKIP_DUPLICATE, null, "Repeated from row " + prior.rowNumber(), null));
                } else {
                    out.add(stored(r, CONFLICT, "ID_REPEATED_WITH_DIFFERENT_NAME",
                        "Same Student ID as row " + prior.rowNumber() + " but a different name", null));
                }
                continue;
            }
            firstByNumber.put(key, r);

            ExistingUser existing = existingByNumber.get(key);
            if (existing == null) {
                out.add(stored(r, CREATE, null, "New account", null));
            } else {
                out.add(classifyExisting(r, existing, admin, caller.getId(), known.contains(existing.id()), enrollments));
            }
        }
        return out;
    }

    private StoredRow classifyExisting(NormalizedRow r, ExistingUser existing, boolean admin, UUID callerId,
                                       boolean knownToCaller, Map<UUID, String> enrollments) {
        boolean usable = EnrollmentRules.mayEnroll(admin, callerId, existing.role(), existing.status(),
            existing.createdBy(), knownToCaller);
        if (!usable) {
            return stored(r, CONFLICT, "ID_UNAVAILABLE", UNAVAILABLE_MESSAGE, null);
        }
        if (!sameName(existing.lastName(), existing.firstName(), r.lastName(), r.firstName())) {
            if (admin || knownToCaller) {
                return stored(r, CONFLICT, "ID_NAME_MISMATCH",
                    "This Student ID is already registered under a different name", existing.id());
            }
            return stored(r, CONFLICT, "ID_UNAVAILABLE", UNAVAILABLE_MESSAGE, null);
        }
        if ("ENROLLED".equals(enrollments.get(existing.id()))) {
            return stored(r, SKIP_DUPLICATE, null, "Already enrolled in this class", existing.id());
        }
        String note = "REMOVED".equals(enrollments.get(existing.id()))
            ? "Existing account; previously removed from this class, will be re-enrolled"
            : "Existing account; will be enrolled";
        return stored(r, MATCH, null, note, existing.id());
    }

    private static StoredRow stored(NormalizedRow r, String action, String kind, String message, UUID matchedUser) {
        return new StoredRow(r.rowNumber(), r.studentNumber(), r.lastName(), r.firstName(), r.middleName(),
            r.email(), r.program(), r.section(), action, kind, null, message, matchedUser);
    }

    private static boolean sameName(String lastA, String firstA, String lastB, String firstB) {
        return lower(lastA).equals(lower(lastB)) && lower(firstA).equals(lower(firstB));
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
