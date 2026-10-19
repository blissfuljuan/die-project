package com.blissfuljuan.dieapi.academic;

import com.blissfuljuan.dieapi.PostgresIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * P1-VS03: exactly one designated Owner per workspace, delegated coordinators with per-coordinator capabilities,
 * audited ownership transfer, account-lifecycle guards and the concurrency backstops. Written, not yet run.
 */
class WorkspaceOwnershipIntegrationTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "correct horse battery staple";
    private static final String WS = "/api/v1/workspaces";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired PlatformTransactionManager txManager;

    final ExecutorService pool = Executors.newFixedThreadPool(4);
    final Map<UUID, Cookie> sessions = new HashMap<>();

    // admin; owner = creator of W; peer = plain coordinator (default capabilities); delegate = MANAGE_COORDINATORS;
    // stranger = coordinator of nothing; gone = deactivated coordinator; p1 = enrolled participant; p2 = outsider participant
    UUID admin, owner, peer, delegate, stranger, gone, p1, p2;
    UUID w;

    @BeforeEach
    void seed() throws Exception {
        sessions.clear();
        admin = user("admin@x.edu", "ADMIN", "ACTIVE", null);
        owner = user("owner@x.edu", "COORDINATOR", "ACTIVE", null);
        peer = user("peer@x.edu", "COORDINATOR", "ACTIVE", null);
        delegate = user("delegate@x.edu", "COORDINATOR", "ACTIVE", null);
        stranger = user("stranger@x.edu", "COORDINATOR", "ACTIVE", null);
        gone = user("gone@x.edu", "COORDINATOR", "DEACTIVATED", null);
        p1 = user("p1@x.edu", "PARTICIPANT", "ACTIVE", "S-1");
        p2 = user("p2@x.edu", "PARTICIPANT", "ACTIVE", "S-2");

        MvcResult created = call(owner, HttpMethod.POST, WS, workspaceBody("CS900"));
        assertThat(status(created)).isEqualTo(201);
        w = UUID.fromString(JsonPath.read(body(created), "$.id"));
        jdbc.update("INSERT INTO enrollment (class_id, student_id) VALUES (?, ?)", w, p1);
    }

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    // ---- creation ----

    @Test
    void aCoordinatorWhoCreatesAWorkspaceBecomesItsOwnerWithEveryCapability() throws Exception {
        MvcResult view = call(owner, HttpMethod.GET, WS + "/" + w, null);
        assertThat((String) JsonPath.read(body(view), "$.ownerId")).isEqualTo(owner.toString());
        assertThat((List<String>) JsonPath.read(body(view), "$.myCapabilities"))
            .containsExactlyInAnyOrder("MANAGE_SETTINGS", "MANAGE_ROSTER", "MANAGE_PROJECTS", "MANAGE_COORDINATORS",
                "CONFIGURE_REQUIREMENTS", "CONFIGURE_WORKFLOW", "MANAGE_TEMPLATES", "MANAGE_STORAGE",
                "INITIATE_EVALUATION", "REVIEW", "APPROVE", "PUBLISH");
        assertThat((List<Boolean>) JsonPath.read(body(view), "$.coordinators[?(@.id=='" + owner + "')].owner")).containsExactly(true);
        assertThat(jdbc.queryForObject("SELECT owner_id FROM academic_class WHERE id = ?", UUID.class, w)).isEqualTo(owner);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM class_instructor WHERE class_id = ?", Integer.class, w)).isEqualTo(1);

        // A coordinator cannot make somebody else the owner at creation time.
        String other = workspaceBody("CS901").replace("}", ",\"ownerId\":\"" + peer + "\"}");
        assertProblem(call(owner, HttpMethod.POST, WS, other), 400, "WORKSPACE_OWNER_INVALID");
    }

    @Test
    void anAdministratorMustNameTheOwnerAndTheOwnerIsAlwaysAssigned() throws Exception {
        assertProblem(call(admin, HttpMethod.POST, WS, workspaceBody("CS902")), 400, "WORKSPACE_OWNER_REQUIRED");
        assertProblem(call(admin, HttpMethod.POST, WS, workspaceBody("CS902").replace("}", ",\"coordinatorIds\":[\"" + peer + "\"]}")),
            400, "WORKSPACE_OWNER_REQUIRED");
        assertProblem(call(admin, HttpMethod.POST, WS, workspaceBody("CS902").replace("}", ",\"ownerId\":\"" + p2 + "\"}")),
            400, "COORDINATOR_NOT_ASSIGNABLE");
        assertProblem(call(admin, HttpMethod.POST, WS, workspaceBody("CS902").replace("}", ",\"ownerId\":\"" + gone + "\"}")),
            400, "COORDINATOR_NOT_ASSIGNABLE");

        MvcResult ok = call(admin, HttpMethod.POST, WS,
            workspaceBody("CS902").replace("}", ",\"ownerId\":\"" + peer + "\",\"coordinatorIds\":[\"" + delegate + "\"]}"));
        assertThat(status(ok)).isEqualTo(201);
        assertThat((String) JsonPath.read(body(ok), "$.ownerId")).isEqualTo(peer.toString());
        assertThat((List<String>) JsonPath.read(body(ok), "$.coordinators[*].id")).containsExactlyInAnyOrder(peer.toString(), delegate.toString());
        // the administrator who created it has administrative capabilities only
        assertThat((List<String>) JsonPath.read(body(ok), "$.myCapabilities"))
            .containsExactlyInAnyOrder("MANAGE_SETTINGS", "MANAGE_ROSTER", "MANAGE_PROJECTS", "MANAGE_COORDINATORS");
    }

    @Test
    void theOldReplaceAllEndpointIsGone() throws Exception {
        assertThat(status(call(admin, HttpMethod.PUT, WS + "/" + w + "/coordinators", "{\"coordinatorIds\":[\"" + owner + "\"]}"))).isEqualTo(405);
    }

    // ---- coordinators and capabilities ----

    @Test
    void coordinatorsAreListedForStaffAndNeverForParticipantsOrOutsiders() throws Exception {
        add(owner, peer, null);
        MvcResult list = call(peer, HttpMethod.GET, coordinators(), null);
        assertThat(status(list)).isEqualTo(200);
        assertThat((List<String>) JsonPath.read(body(list), "$[?(@.userId=='" + peer + "')].capabilities[*]"))
            .containsExactlyInAnyOrder("MANAGE_ROSTER", "MANAGE_PROJECTS");
        assertThat((List<Boolean>) JsonPath.read(body(list), "$[?(@.userId=='" + owner + "')].owner")).containsExactly(true);
        assertThat(status(call(admin, HttpMethod.GET, coordinators(), null))).isEqualTo(200);
        assertThat(status(call(p1, HttpMethod.GET, coordinators(), null))).as("enrolled participant").isEqualTo(403);
        assertThat(status(call(p2, HttpMethod.GET, coordinators(), null))).as("outsider participant").isEqualTo(404);
        assertThat(status(call(stranger, HttpMethod.GET, coordinators(), null))).as("coordinator of another workspace").isEqualTo(404);
    }

    @Test
    void addingACoordinatorValidatesTheTargetAndTheCapabilities() throws Exception {
        MvcResult added = add(owner, peer, null);
        assertThat(status(added)).isEqualTo(201);
        assertThat((List<String>) JsonPath.read(body(added), "$.capabilities")).containsExactly("MANAGE_ROSTER", "MANAGE_PROJECTS");
        assertThat((Boolean) JsonPath.read(body(added), "$.owner")).isFalse();

        assertProblem(add(owner, peer, null), 409, "COORDINATOR_ALREADY_ASSIGNED");
        assertProblem(add(owner, p2, null), 400, "COORDINATOR_NOT_ASSIGNABLE");
        assertProblem(add(owner, admin, null), 400, "COORDINATOR_NOT_ASSIGNABLE");
        assertProblem(add(owner, gone, null), 400, "COORDINATOR_NOT_ASSIGNABLE");
        assertProblem(add(owner, delegate, "[\"FLY\"]"), 400, "INVALID_CAPABILITY");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM class_instructor WHERE class_id = ?", Integer.class, w)).isEqualTo(2);
        assertThat(auditCount("COORDINATOR_ADDED")).isEqualTo(1);
    }

    @Test
    void aCoordinatorWithoutTheCapabilityIsRefusedAndAChangeTakesEffectOnTheNextRequest() throws Exception {
        add(owner, peer, null);                                   // MANAGE_ROSTER + MANAGE_PROJECTS only
        String settings = "{\"version\":" + version() + ",\"section\":\"Z\"}";

        assertProblem(call(peer, HttpMethod.PATCH, WS + "/" + w, settings), 403, "ACCESS_DENIED");
        assertProblem(add(peer, delegate, null), 403, "ACCESS_DENIED");
        assertThat(status(call(peer, HttpMethod.GET, WS + "/" + w + "/enrollments", null))).as("roster capability").isEqualTo(200);
        assertThat((List<String>) JsonPath.read(body(call(peer, HttpMethod.GET, WS + "/" + w, null)), "$.myCapabilities"))
            .containsExactly("MANAGE_ROSTER", "MANAGE_PROJECTS");

        // grant settings: the peer's existing session is honoured on the very next request
        assertThat(status(setCapabilities(owner, peer, "[\"MANAGE_SETTINGS\",\"MANAGE_ROSTER\"]"))).isEqualTo(200);
        assertThat(status(call(peer, HttpMethod.PATCH, WS + "/" + w, settings))).isEqualTo(200);

        // revoke the roster capability: reading the roster is refused immediately
        assertThat(status(setCapabilities(owner, peer, "[\"MANAGE_SETTINGS\"]"))).isEqualTo(200);
        assertProblem(call(peer, HttpMethod.GET, WS + "/" + w + "/enrollments", null), 403, "ACCESS_DENIED");
        assertProblem(call(peer, HttpMethod.POST, WS + "/" + w + "/enrollments", "{\"participantId\":\"" + p2 + "\"}"), 403, "ACCESS_DENIED");
        // ... and so are administrative project operations
        assertProblem(call(peer, HttpMethod.POST, WS + "/" + w + "/projects", "{\"kind\":\"TEAM\",\"title\":\"T\"}"), 403, "ACCESS_DENIED");
        assertThat(auditCount("COORDINATOR_CAPABILITIES_CHANGED")).isEqualTo(2);
    }

    @Test
    void delegatedCoordinatorsCannotGrantWhatTheyDoNotHold() throws Exception {
        add(owner, peer, null);
        add(owner, delegate, "[\"MANAGE_COORDINATORS\",\"MANAGE_ROSTER\"]");

        assertProblem(setCapabilities(delegate, peer, "[\"MANAGE_SETTINGS\"]"), 403, "CAPABILITY_ESCALATION_FORBIDDEN");
        assertProblem(add(delegate, stranger, "[\"MANAGE_PROJECTS\"]"), 403, "CAPABILITY_ESCALATION_FORBIDDEN");
        assertProblem(setCapabilities(delegate, delegate, "[\"MANAGE_COORDINATORS\",\"MANAGE_ROSTER\",\"APPROVE\"]"), 403, "CAPABILITY_ESCALATION_FORBIDDEN");
        assertThat(status(setCapabilities(delegate, peer, "[\"MANAGE_ROSTER\"]"))).isEqualTo(200);
        assertThat(status(add(delegate, stranger, "[\"MANAGE_ROSTER\"]"))).isEqualTo(201);
        // an administrator may delegate any capability
        assertThat(status(setCapabilities(admin, peer, "[\"MANAGE_SETTINGS\",\"APPROVE\"]"))).isEqualTo(200);
    }

    @Test
    void theOwnerCannotBeRemovedNorHaveCapabilitiesReduced() throws Exception {
        add(owner, delegate, "[\"MANAGE_COORDINATORS\"]");
        assertProblem(call(owner, HttpMethod.DELETE, coordinators() + "/" + owner, null), 409, "WORKSPACE_OWNER_REMOVAL_FORBIDDEN");
        assertProblem(call(delegate, HttpMethod.DELETE, coordinators() + "/" + owner, null), 409, "WORKSPACE_OWNER_REMOVAL_FORBIDDEN");
        assertProblem(call(admin, HttpMethod.DELETE, coordinators() + "/" + owner, null), 409, "WORKSPACE_OWNER_REMOVAL_FORBIDDEN");
        assertProblem(setCapabilities(admin, owner, "[\"MANAGE_ROSTER\"]"), 409, "WORKSPACE_OWNER_CAPABILITIES_FIXED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM class_instructor WHERE class_id = ? AND user_id = ?", Integer.class, w, owner)).isEqualTo(1);

        // removing an ordinary coordinator works and is audited
        assertThat(status(call(owner, HttpMethod.DELETE, coordinators() + "/" + delegate, null))).isEqualTo(204);
        assertProblem(call(owner, HttpMethod.DELETE, coordinators() + "/" + delegate, null), 404, "COORDINATOR_NOT_FOUND");
        assertThat(auditCount("COORDINATOR_REMOVED")).isEqualTo(1);
        assertThat(status(call(delegate, HttpMethod.GET, WS + "/" + w, null))).as("removed coordinator loses access").isEqualTo(404);
    }

    @Test
    void theDatabaseItselfRefusesToUnassignTheOwner() {
        assertThatThrownBy(() -> jdbc.update("DELETE FROM class_instructor WHERE class_id = ? AND user_id = ?", w, owner))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM class_instructor WHERE class_id = ? AND user_id = ?", Integer.class, w, owner)).isEqualTo(1);
    }

    // ---- ownership transfer ----

    @Test
    void ownershipTransferIsAuditedAndLeavesSessionsAlone() throws Exception {
        add(owner, peer, null);
        Cookie ownerSession = as(owner);

        assertThat(status(call(owner, HttpMethod.PUT, WS + "/" + w + "/owner", "{\"userId\":\"" + peer + "\"}"))).isEqualTo(204);

        assertThat(jdbc.queryForObject("SELECT owner_id FROM academic_class WHERE id = ?", UUID.class, w)).isEqualTo(peer);
        assertThat((String) JsonPath.read(body(call(peer, HttpMethod.GET, WS + "/" + w, null)), "$.ownerId")).isEqualTo(peer.toString());
        assertThat(jdbc.queryForObject("SELECT details->>'from' FROM audit_log WHERE action = 'WORKSPACE_OWNER_TRANSFERRED'", String.class)).isEqualTo(owner.toString());
        assertThat(jdbc.queryForObject("SELECT details->>'to' FROM audit_log WHERE action = 'WORKSPACE_OWNER_TRANSFERRED'", String.class)).isEqualTo(peer.toString());
        assertThat(jdbc.queryForObject("SELECT actor_user_id FROM audit_log WHERE action = 'WORKSPACE_OWNER_TRANSFERRED'", UUID.class)).isEqualTo(owner);

        // the previous owner's session still works (still an assigned coordinator), but they are no longer the Owner
        assertThat(sessions.get(owner)).isSameAs(ownerSession);
        assertThat(status(call(owner, HttpMethod.GET, WS + "/" + w, null))).isEqualTo(200);
        assertProblem(call(owner, HttpMethod.PUT, WS + "/" + w + "/owner", "{\"userId\":\"" + owner + "\"}"), 403, "ACCESS_DENIED");
        // the new owner can remove the previous one; removing the Owner is still refused
        assertThat(status(call(peer, HttpMethod.DELETE, coordinators() + "/" + owner, null))).isEqualTo(204);
        assertProblem(call(peer, HttpMethod.DELETE, coordinators() + "/" + peer, null), 409, "WORKSPACE_OWNER_REMOVAL_FORBIDDEN");
    }

    @Test
    void transferTargetsMustBeAssignedActiveCoordinatorsAndCallersMustBeEntitled() throws Exception {
        add(owner, peer, null);
        String path = WS + "/" + w + "/owner";

        assertProblem(call(owner, HttpMethod.PUT, path, "{\"userId\":\"" + stranger + "\"}"), 400, "WORKSPACE_OWNER_INVALID");
        assertProblem(call(owner, HttpMethod.PUT, path, "{\"userId\":\"" + p1 + "\"}"), 400, "WORKSPACE_OWNER_INVALID");
        assertProblem(call(owner, HttpMethod.PUT, path, "{\"userId\":\"" + UUID.randomUUID() + "\"}"), 400, "WORKSPACE_OWNER_INVALID");
        jdbc.update("INSERT INTO class_instructor (class_id, user_id) VALUES (?, ?)", w, gone);
        assertProblem(call(owner, HttpMethod.PUT, path, "{\"userId\":\"" + gone + "\"}"), 400, "COORDINATOR_NOT_ASSIGNABLE");

        assertProblem(call(peer, HttpMethod.PUT, path, "{\"userId\":\"" + peer + "\"}"), 403, "ACCESS_DENIED");
        assertProblem(call(p1, HttpMethod.PUT, path, "{\"userId\":\"" + peer + "\"}"), 403, "ACCESS_DENIED");
        assertThat(status(call(stranger, HttpMethod.PUT, path, "{\"userId\":\"" + peer + "\"}"))).isEqualTo(404);
        assertThat(status(call(p2, HttpMethod.PUT, path, "{\"userId\":\"" + peer + "\"}"))).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT owner_id FROM academic_class WHERE id = ?", UUID.class, w)).isEqualTo(owner);
        assertThat(auditCount("WORKSPACE_OWNER_TRANSFERRED")).isZero();

        // transferring to the current owner is a no-op without an audit entry; an administrator can recover ownership
        assertThat(status(call(owner, HttpMethod.PUT, path, "{\"userId\":\"" + owner + "\"}"))).isEqualTo(204);
        assertThat(auditCount("WORKSPACE_OWNER_TRANSFERRED")).isZero();
        assertThat(status(call(admin, HttpMethod.PUT, path, "{\"userId\":\"" + peer + "\"}"))).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT owner_id FROM academic_class WHERE id = ?", UUID.class, w)).isEqualTo(peer);
        assertThat(auditCount("WORKSPACE_OWNER_TRANSFERRED")).isEqualTo(1);
    }

    // ---- account lifecycle guards ----

    @Test
    void anOwnerOfAnActiveWorkspaceCanNeitherBeDeactivatedNorReRoledUntilOwnershipMoves() throws Exception {
        add(owner, peer, null);
        long accountVersion = JsonPath.<Number>read(body(call(admin, HttpMethod.GET, "/api/v1/users/" + owner, null)), "$.version").longValue();

        MvcResult deactivate = call(admin, HttpMethod.POST, "/api/v1/users/" + owner + "/deactivate", null);
        assertProblem(deactivate, 409, "WORKSPACE_OWNER_TRANSFER_REQUIRED");
        // the message tells how many workspaces need a transfer, not which ones
        String detail = JsonPath.read(body(deactivate), "$.detail");
        assertThat(detail).contains("1 active workspace").doesNotContain(w.toString()).doesNotContain("CS900");

        assertProblem(call(admin, HttpMethod.PATCH, "/api/v1/users/" + owner, "{\"version\":" + accountVersion + ",\"role\":\"ADMIN\"}"),
            409, "WORKSPACE_OWNER_TRANSFER_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT status FROM app_user WHERE id = ?", String.class, owner)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT role FROM app_user WHERE id = ?", String.class, owner)).isEqualTo("COORDINATOR");

        assertThat(status(call(owner, HttpMethod.PUT, WS + "/" + w + "/owner", "{\"userId\":\"" + peer + "\"}"))).isEqualTo(204);
        assertThat(status(call(admin, HttpMethod.POST, "/api/v1/users/" + owner + "/deactivate", null))).isEqualTo(200);
    }

    @Test
    void archivedWorkspacesDoNotBlockDeactivationButCannotBeReactivatedWithoutAUsableOwner() throws Exception {
        add(owner, peer, null);
        assertThat(status(call(owner, HttpMethod.POST, WS + "/" + w + "/archive", null))).isEqualTo(200);

        assertThat(status(call(admin, HttpMethod.POST, "/api/v1/users/" + owner + "/deactivate", null))).as("archived workspace does not block").isEqualTo(200);

        assertProblem(call(admin, HttpMethod.POST, WS + "/" + w + "/unarchive", null), 409, "WORKSPACE_OWNER_INACTIVE");
        // owner recovery works while archived; afterwards the workspace can be reactivated
        assertThat(status(call(admin, HttpMethod.PUT, WS + "/" + w + "/owner", "{\"userId\":\"" + peer + "\"}"))).isEqualTo(204);
        assertThat(status(call(admin, HttpMethod.POST, WS + "/" + w + "/unarchive", null))).isEqualTo(200);
    }

    @Test
    void anAdministratorGetsAdministrativeCapabilitiesAndParticipantsGetNone() throws Exception {
        assertThat((List<String>) JsonPath.read(body(call(admin, HttpMethod.GET, WS + "/" + w, null)), "$.myCapabilities"))
            .containsExactlyInAnyOrder("MANAGE_SETTINGS", "MANAGE_ROSTER", "MANAGE_PROJECTS", "MANAGE_COORDINATORS");
        assertThat((List<String>) JsonPath.read(body(call(p1, HttpMethod.GET, WS + "/" + w, null)), "$.myCapabilities")).isEmpty();
        MvcResult list = call(owner, HttpMethod.GET, WS, null);
        assertThat((List<String>) JsonPath.read(body(list), "$.items[0].myCapabilities")).hasSize(12);
        assertThat((String) JsonPath.read(body(list), "$.items[0].ownerId")).isEqualTo(owner.toString());
    }

    // ---- concurrency ----

    @Test
    void concurrentTransferAndRemovalNeverLeaveTheWorkspaceWithoutAnAssignedOwner() throws Exception {
        add(owner, peer, null);
        add(owner, delegate, "[\"MANAGE_COORDINATORS\"]");
        as(owner);
        as(delegate); // sessions are created up front: the pool threads below must not touch the session cache

        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        Future<?> holder = holdWorkspaceLock(locked, release, () -> { });
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        Future<MvcResult> transfer = pool.submit(() -> call(owner, HttpMethod.PUT, WS + "/" + w + "/owner", "{\"userId\":\"" + peer + "\"}"));
        Future<MvcResult> remove = pool.submit(() -> call(delegate, HttpMethod.DELETE, coordinators() + "/" + peer, null));
        Thread.sleep(800);
        assertThat(transfer.isDone() || remove.isDone()).as("both must be parked on the workspace lock").isFalse();
        release.countDown();
        holder.get(15, TimeUnit.SECONDS);

        int transferStatus = status(transfer.get(15, TimeUnit.SECONDS));
        int removeStatus = status(remove.get(15, TimeUnit.SECONDS));
        // serialized by the workspace lock: either the transfer won (peer is Owner, so removal is refused)
        // or the removal won (peer is gone, so the transfer target is invalid)
        boolean transferFirst = transferStatus == 204 && removeStatus == 409;
        boolean removeFirst = removeStatus == 204 && transferStatus == 400;
        assertThat(transferFirst || removeFirst).as("transfer=%s remove=%s", transferStatus, removeStatus).isTrue();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM academic_class c JOIN class_instructor ci ON ci.class_id = c.id AND ci.user_id = c.owner_id
            WHERE c.id = ?""", Integer.class, w)).as("owner is an assigned coordinator").isEqualTo(1);
    }

    @Test
    void aTransferRacingWithTheDeactivationOfItsTargetIsRefused() throws Exception {
        add(owner, peer, null);
        as(owner);

        // A deactivation of the target is in flight: it holds the account row (FOR UPDATE semantics via UPDATE).
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        Future<?> holder = pool.submit(() -> new TransactionTemplate(txManager).executeWithoutResult(status -> {
            jdbc.update("UPDATE app_user SET status = 'DEACTIVATED' WHERE id = ?", peer);
            locked.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        Future<MvcResult> transfer = pool.submit(() -> call(owner, HttpMethod.PUT, WS + "/" + w + "/owner", "{\"userId\":\"" + peer + "\"}"));
        Thread.sleep(800);
        assertThat(transfer.isDone()).as("transfer waits for the target's account row").isFalse();
        release.countDown();
        holder.get(15, TimeUnit.SECONDS);

        assertProblem(transfer.get(15, TimeUnit.SECONDS), 400, "COORDINATOR_NOT_ASSIGNABLE");
        assertThat(jdbc.queryForObject("SELECT owner_id FROM academic_class WHERE id = ?", UUID.class, w)).isEqualTo(owner);
    }

    @Test
    void aDeactivationRacingWithATransferToItsTargetSeesTheNewOwnership() throws Exception {
        add(owner, peer, null);
        as(admin);

        // The transfer is in flight: it holds the workspace lock and a share lock on the target's account row.
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        Future<?> holder = holdWorkspaceLock(locked, release, () -> {
            jdbc.queryForObject("SELECT id FROM app_user WHERE id = ? FOR SHARE", UUID.class, peer);
            jdbc.update("UPDATE academic_class SET owner_id = ? WHERE id = ?", peer, w);
        });
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        Future<MvcResult> deactivate = pool.submit(() -> call(admin, HttpMethod.POST, "/api/v1/users/" + peer + "/deactivate", null));
        Thread.sleep(800);
        assertThat(deactivate.isDone()).as("deactivation waits for the account row").isFalse();
        release.countDown();
        holder.get(15, TimeUnit.SECONDS);

        assertProblem(deactivate.get(15, TimeUnit.SECONDS), 409, "WORKSPACE_OWNER_TRANSFER_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT status FROM app_user WHERE id = ?", String.class, peer)).isEqualTo("ACTIVE");
    }

    // ---- helpers ----

    /** Plays a concurrent staff action: runs {@code change} while holding the workspace row lock until released. */
    private Future<?> holdWorkspaceLock(CountDownLatch locked, CountDownLatch release, Runnable change) {
        return pool.submit(() -> new TransactionTemplate(txManager).executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM academic_class WHERE id = ? FOR UPDATE", UUID.class, w);
            change.run();
            locked.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }));
    }

    private String coordinators() {
        return WS + "/" + w + "/coordinators";
    }

    private MvcResult add(UUID actor, UUID target, String capabilitiesJson) throws Exception {
        String body = "{\"userId\":\"" + target + "\"" + (capabilitiesJson == null ? "" : ",\"capabilities\":" + capabilitiesJson) + "}";
        return call(actor, HttpMethod.POST, coordinators(), body);
    }

    private MvcResult setCapabilities(UUID actor, UUID target, String capabilitiesJson) throws Exception {
        return call(actor, HttpMethod.PATCH, coordinators() + "/" + target, "{\"capabilities\":" + capabilitiesJson + "}");
    }

    private long version() {
        return jdbc.queryForObject("SELECT version FROM academic_class WHERE id = ?", Long.class, w);
    }

    private int auditCount(String action) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = ?", Integer.class, action);
    }

    private static String workspaceBody(String code) {
        return "{\"academicYear\":\"2025-2026\",\"semester\":\"1st\",\"subjectCode\":\"" + code + "\",\"subjectName\":\"Subject\","
            + "\"section\":\"A\",\"teamFormation\":\"PARTICIPANT_FORMED\",\"teamMinSize\":1,\"teamMaxSize\":4}";
    }

    private UUID user(String email, String role, String status, String studentNumber) {
        return jdbc.queryForObject("""
            INSERT INTO app_user (role, status, student_number, last_name, first_name, email, email_verified_at, password_hash)
            VALUES (?, ?, ?, ?, 'Test', ?, now(), ?) RETURNING id
            """, UUID.class, role, status, studentNumber, email.substring(0, email.indexOf('@')), email, encoder.encode(PASSWORD));
    }

    private Cookie as(UUID user) throws Exception {
        Cookie existing = sessions.get(user);
        if (existing != null) return existing;
        String email = jdbc.queryForObject("SELECT email FROM app_user WHERE id = ?", String.class, user);
        MvcResult r = mvc.perform(request(HttpMethod.POST, "/api/v1/auth/login").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")).andReturn();
        assertThat(r.getResponse().getStatus()).as("login " + email).isEqualTo(200);
        Cookie cookie = r.getResponse().getHeaders("Set-Cookie").stream()
            .filter(h -> h.startsWith("SESSION="))
            .map(h -> new Cookie("SESSION", h.substring("SESSION=".length(), h.indexOf(';'))))
            .findFirst().orElseThrow();
        sessions.put(user, cookie);
        return cookie;
    }

    private MvcResult call(UUID actor, HttpMethod method, String path, String json) throws Exception {
        Cookie session = actor == null ? null : as(actor);
        MockHttpServletRequestBuilder req = request(method, path);
        if (method != HttpMethod.GET) req.with(csrf());
        if (session != null) req.cookie(session);
        if (json != null) req.contentType(MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(req).andReturn();
    }

    private static int status(MvcResult r) { return r.getResponse().getStatus(); }

    private static String body(MvcResult r) throws Exception { return r.getResponse().getContentAsString(); }

    private static void assertProblem(MvcResult r, int expectedStatus, String expectedCode) throws Exception {
        assertThat(status(r)).as(body(r)).isEqualTo(expectedStatus);
        assertThat((String) JsonPath.read(body(r), "$.code")).isEqualTo(expectedCode);
    }
}
