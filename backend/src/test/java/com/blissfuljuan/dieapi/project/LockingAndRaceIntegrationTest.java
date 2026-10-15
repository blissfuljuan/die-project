package com.blissfuljuan.dieapi.project;

import com.blissfuljuan.dieapi.PostgresIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Deterministic race tests (security review M1 and M4). A "holder" transaction plays the part of a
 * concurrent staff action: it takes the class row lock and keeps it while the request under test runs
 * into it. No sleeping-and-hoping: the interleaving is forced with latches.
 */
class LockingAndRaceIntegrationTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "correct horse battery staple";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired PlatformTransactionManager txManager;

    final ExecutorService pool = Executors.newFixedThreadPool(4);
    UUID rep, member, invitee, instructor, classId, projectId, invitationId;
    Cookie repCookie, inviteeCookie;

    @BeforeEach
    void seed() throws Exception {
        instructor = user("i@x.edu", "INSTRUCTOR", null);
        rep = user("rep@x.edu", "STUDENT", "S-1");
        member = user("member@x.edu", "STUDENT", "S-2");
        invitee = user("invitee@x.edu", "STUDENT", "S-3");
        classId = jdbc.queryForObject("""
            INSERT INTO academic_class (academic_year, semester, subject_code, subject_name, section, team_formation, team_min_size, team_max_size)
            VALUES ('2025-2026', '1st', 'CS1', 'S', 'A', 'STUDENT_FORMED', 2, 4) RETURNING id
            """, UUID.class);
        jdbc.update("INSERT INTO class_instructor (class_id, user_id) VALUES (?, ?)", classId, instructor);
        for (UUID s : new UUID[] {rep, member, invitee}) jdbc.update("INSERT INTO enrollment (class_id, student_id) VALUES (?, ?)", classId, s);
        projectId = jdbc.queryForObject("INSERT INTO project (class_id, title, kind, team_name) VALUES (?, 'Team', 'TEAM', 'Team') RETURNING id", UUID.class, classId);
        jdbc.update("INSERT INTO project_member (project_id, user_id, role) VALUES (?, ?, 'REPRESENTATIVE')", projectId, rep);
        jdbc.update("INSERT INTO project_member (project_id, user_id, role) VALUES (?, ?, 'MEMBER')", projectId, member);
        invitationId = jdbc.queryForObject("INSERT INTO team_invitation (project_id, invited_user_id, invited_by, expires_at) VALUES (?, ?, ?, now() + interval '1 day') RETURNING id",
            UUID.class, projectId, invitee, rep);
        repCookie = login("rep@x.edu");
        inviteeCookie = login("invitee@x.edu");
    }

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    private UUID user(String email, String role, String number) {
        return jdbc.queryForObject("""
            INSERT INTO app_user (role, status, student_number, last_name, first_name, email, email_verified_at, password_hash)
            VALUES (?, 'ACTIVE', ?, ?, 'T', ?, now(), ?) RETURNING id
            """, UUID.class, role, number, email.substring(0, email.indexOf('@')), email, encoder.encode(PASSWORD));
    }

    private Cookie login(String email) throws Exception {
        MvcResult r = mvc.perform(request(HttpMethod.POST, "/api/v1/auth/login").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return r.getResponse().getHeaders("Set-Cookie").stream().filter(h -> h.startsWith("SESSION="))
            .map(h -> new Cookie("SESSION", h.substring("SESSION=".length(), h.indexOf(';')))).findFirst().orElseThrow();
    }

    private MvcResult call(Cookie cookie, HttpMethod method, String path, String json) throws Exception {
        MockHttpServletRequestBuilder req = request(method, path).with(csrf()).cookie(cookie);
        if (json != null) req.contentType(MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(req).andReturn();
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    /** Holds the class row lock (like any staff mutation) after running {@code concurrentChange}, until released. */
    private Future<?> holdClassLock(Runnable concurrentChange, CountDownLatch locked, CountDownLatch release) {
        return pool.submit(() -> tx().executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM academic_class WHERE id = ? FOR UPDATE", UUID.class, classId);
            concurrentChange.run();
            locked.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }));
    }

    // ---- M1: lock order class -> project -> invitation ----

    @Test
    void acceptingAnInvitationDoesNotLockTheInvitationBeforeTheClass() throws Exception {
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        Future<?> holder = holdClassLock(() -> { }, locked, release);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        Future<MvcResult> accept = pool.submit(() -> call(inviteeCookie, HttpMethod.POST, "/api/v1/invitations/" + invitationId + "/accept", null));
        Thread.sleep(800);                                   // the request is now parked on the class lock
        assertThat(accept.isDone()).as("accept must be waiting for the class lock").isFalse();

        // If accept had already locked its invitation row (the old order), this NOWAIT lock would fail with 55P03,
        // and a staff action touching invitations while holding the class lock could deadlock with it.
        tx().executeWithoutResult(s -> jdbc.queryForObject("SELECT id FROM team_invitation WHERE id = ? FOR UPDATE NOWAIT", UUID.class, invitationId));

        release.countDown();
        holder.get(15, TimeUnit.SECONDS);
        assertThat(accept.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM project_member WHERE project_id = ? AND user_id = ? AND left_at IS NULL", Integer.class, projectId, invitee)).isEqualTo(1);
    }

    @Test
    void staffRemovingTheInviteeWhileTheyAcceptEndsCleanlyNotInADeadlock() throws Exception {
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        Future<?> holder = holdClassLock(() -> {
            // what ClassService.unenroll does while holding the class lock
            jdbc.update("UPDATE enrollment SET status = 'REMOVED', removed_at = now() WHERE class_id = ? AND student_id = ?", classId, invitee);
            jdbc.update("UPDATE team_invitation SET status = 'CANCELLED' WHERE id = ?", invitationId);
        }, locked, release);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        Future<MvcResult> accept = pool.submit(() -> call(inviteeCookie, HttpMethod.POST, "/api/v1/invitations/" + invitationId + "/accept", null));
        Thread.sleep(500);
        release.countDown();
        holder.get(15, TimeUnit.SECONDS);

        int status = accept.get(15, TimeUnit.SECONDS).getResponse().getStatus();
        assertThat(status).as("a deadlock victim would be a 500").isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM project_member WHERE project_id = ? AND user_id = ?", Integer.class, projectId, invitee)).isZero();
    }

    // ---- M4: permissions are re-checked after the locks are held ----

    @Test
    void aRepresentativeRemovedWhileInvitingCannotSendTheInvitation() throws Exception {
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        UUID other = user("other@x.edu", "STUDENT", "S-4");
        jdbc.update("INSERT INTO enrollment (class_id, student_id) VALUES (?, ?)", classId, other);
        Future<?> holder = holdClassLock(() -> jdbc.update(
            "UPDATE project_member SET left_at = now() WHERE project_id = ? AND user_id = ? AND left_at IS NULL", projectId, rep), locked, release);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        // The representative still looks like a representative to everything committed so far.
        Future<MvcResult> invite = pool.submit(() -> call(repCookie, HttpMethod.POST, "/api/v1/projects/" + projectId + "/invitations", "{\"studentId\":\"" + other + "\"}"));
        Thread.sleep(800);
        assertThat(invite.isDone()).isFalse();
        release.countDown();
        holder.get(15, TimeUnit.SECONDS);

        assertThat(invite.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM team_invitation WHERE invited_user_id = ?", Integer.class, other)).isZero();
    }

    @Test
    void aRepresentativeRemovedWhileEditingCannotChangeTheProject() throws Exception {
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        long version = jdbc.queryForObject("SELECT version FROM project WHERE id = ?", Long.class, projectId);
        Future<?> holder = holdClassLock(() -> jdbc.update(
            "UPDATE project_member SET left_at = now() WHERE project_id = ? AND user_id = ? AND left_at IS NULL", projectId, rep), locked, release);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        Future<MvcResult> patch = pool.submit(() -> call(repCookie, HttpMethod.PATCH, "/api/v1/projects/" + projectId,
            "{\"version\":" + version + ",\"title\":\"Hijacked\"}"));
        Thread.sleep(800);
        assertThat(patch.isDone()).isFalse();
        release.countDown();
        holder.get(15, TimeUnit.SECONDS);

        assertThat(patch.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT title FROM project WHERE id = ?", String.class, projectId)).isEqualTo("Team");
    }

    @Test
    void aMemberRemovedWhileLeavingDoesNotProduceALeftEvent() throws Exception {
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        jdbc.update("UPDATE academic_class SET students_may_change_teams = TRUE WHERE id = ?", classId);
        Cookie memberCookie = login("member@x.edu");
        Future<?> holder = holdClassLock(() -> jdbc.update(
            "UPDATE project_member SET left_at = now() WHERE project_id = ? AND user_id = ? AND left_at IS NULL", projectId, member), locked, release);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        Future<MvcResult> leave = pool.submit(() -> call(memberCookie, HttpMethod.POST, "/api/v1/projects/" + projectId + "/leave", null));
        Thread.sleep(800);
        release.countDown();
        holder.get(15, TimeUnit.SECONDS);

        assertThat(leave.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'PROJECT_MEMBER_LEFT'", Integer.class)).isZero();
    }
}
