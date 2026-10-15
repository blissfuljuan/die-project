package com.blissfuljuan.die.project.service;

import com.blissfuljuan.die.academic.model.LockedClass;
import com.blissfuljuan.die.academic.repository.ClassRepository;
import com.blissfuljuan.die.access.service.AccessPolicy;
import com.blissfuljuan.die.access.service.AccessPolicy.ProjectRole;
import com.blissfuljuan.die.audit.service.AuditService;
import com.blissfuljuan.die.auth.model.AppUserDetails;
import com.blissfuljuan.die.project.model.InvitationRow;
import com.blissfuljuan.die.project.model.MemberRow;
import com.blissfuljuan.die.project.model.ProjectRow;
import com.blissfuljuan.die.project.repository.ProjectRepository;
import com.blissfuljuan.die.project.view.Classmate;
import com.blissfuljuan.die.project.view.HistoryEntry;
import com.blissfuljuan.die.project.view.InvitationView;
import com.blissfuljuan.die.project.view.MemberView;
import com.blissfuljuan.die.project.view.MyInvitation;
import com.blissfuljuan.die.project.view.ProjectView;
import com.blissfuljuan.die.project.view.TeamPolicy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Projects, teams, membership and invitations.
 *
 * Concurrency: every mutation locks the class row first and the project row second (always in that
 * order), which serializes all team changes inside a class — "one active project per student per class"
 * and team-size limits therefore cannot be raced. Project.version is bumped by membership changes too,
 * and PATCH / PUT members require the caller's last-seen version.
 *
 * Access: AccessPolicy decides first (outsiders and classmates who are not on the team get the same
 * 404 as for a missing project; members who lack the right get 403).
 */
@Service
public class ProjectService {

    public record CreateProject(String kind, String title, String description, String teamName, UUID studentId,
                                List<UUID> memberIds, UUID representativeId, Boolean allowSizeException) {}

    public record UpdateProject(Long version, String title, String description, String teamName) {}

    public record SetMembers(Long version, List<UUID> memberIds, UUID representativeId, Boolean allowSizeException) {}

    private static final int HARD_MAX_TEAM = 50;

    private final ProjectRepository repo;
    private final ClassRepository classes;
    private final AccessPolicy policy;
    private final TeamMembership team;
    private final AuditService audit;
    private final int invitationDays;

    public ProjectService(ProjectRepository repo, ClassRepository classes, AccessPolicy policy, TeamMembership team,
                          AuditService audit, @Value("${die.team.invitation-days:7}") int invitationDays) {
        this.repo = repo;
        this.classes = classes;
        this.policy = policy;
        this.team = team;
        this.audit = audit;
        this.invitationDays = invitationDays;
    }

    // ================= queries =================

    @Transactional(readOnly = true)
    public List<ProjectView> list(AppUserDetails user, UUID classId) {
        policy.requireClassView(user, classId);
        var cls = classes.peek(classId).orElseThrow(AccessPolicy::classNotFound);
        List<ProjectRow> rows = repo.list(classId, policy.visibleProjects(user));
        Map<UUID, List<MemberRow>> members = repo.currentMembersOf(rows.stream().map(ProjectRow::id).toList());
        List<ProjectView> out = new ArrayList<>();
        for (ProjectRow p : rows) {
            ProjectRole role = policy.projectRole(user, p.id());
            out.add(build(p, role, cls, members.getOrDefault(p.id(), List.of())));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public ProjectView get(AppUserDetails user, UUID projectId) {
        ProjectRole role = policy.requireProjectView(user, projectId);
        return viewOf(projectId, role);
    }

    @Transactional(readOnly = true)
    public List<HistoryEntry> history(AppUserDetails user, UUID projectId) {
        policy.requireProjectStaff(user, projectId);
        return repo.history(projectId);
    }

    /** Classmates a student can invite. Only in classes where students form their own teams. */
    @Transactional(readOnly = true)
    public List<Classmate> eligibleClassmates(AppUserDetails user, UUID classId) {
        policy.requireClassView(user, classId);
        var cls = classes.peek(classId).orElseThrow(AccessPolicy::classNotFound);
        if ("STUDENT".equals(user.getRole()) && !"STUDENT_FORMED".equals(cls.teamFormation())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Teams are assigned by your instructor in this class");
        }
        return repo.eligibleClassmates(classId, user.getId());
    }

    @Transactional(readOnly = true)
    public List<MyInvitation> myInvitations(AppUserDetails user) {
        return "STUDENT".equals(user.getRole()) ? repo.pendingFor(user.getId()) : List.of();
    }

    // ================= create / edit =================

    @Transactional
    public ProjectView create(AppUserDetails user, UUID classId, CreateProject req) {
        boolean student = "STUDENT".equals(user.getRole());
        if (student) {
            policy.requireClassView(user, classId);
        } else {
            policy.requireClassManage(user, classId);
        }
        var cls = lockWritableClass(classId);

        String kind = req.kind();
        if (!Set.of("INDIVIDUAL", "TEAM").contains(kind)) throw bad("kind must be INDIVIDUAL or TEAM");
        String title = text(req.title(), "title", 200, true);
        String description = text(req.description(), "description", 2000, false);
        String teamName = text(req.teamName(), "teamName", 100, false);
        if ("TEAM".equals(kind) && teamName == null) teamName = title;
        if ("INDIVIDUAL".equals(kind)) teamName = null;

        UUID projectId;
        if (student) {
            if (!"TEAM".equals(kind) || !"STUDENT_FORMED".equals(cls.teamFormation())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Students can only create teams, and only in classes where students form their own teams");
            }
            if (repo.activeProjectOf(classId, user.getId()).isPresent()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "You already belong to a project in this class");
            }
            projectId = repo.insert(classId, kind, title, description, teamName, user.getId());
            team.add(projectId, user.getId(), "REPRESENTATIVE", user.getId());
        } else if ("INDIVIDUAL".equals(kind)) {
            if (req.studentId() == null) throw bad("studentId is required for an individual project");
            assertEnrolledAndFree(classId, req.studentId(), null);
            projectId = repo.insert(classId, kind, title, description, null, user.getId());
            team.add(projectId, req.studentId(), "MEMBER", user.getId());
        } else {
            List<UUID> members = distinct(req.memberIds());
            if (members.isEmpty()) throw bad("memberIds must list at least one student");
            checkTeamSize(cls, members.size(), Boolean.TRUE.equals(req.allowSizeException()));
            UUID rep = req.representativeId() == null ? members.get(0) : req.representativeId();
            if (!members.contains(rep)) throw bad("representativeId must be one of memberIds");
            for (UUID m : members) assertEnrolledAndFree(classId, m, null);
            projectId = repo.insert(classId, kind, title, description, teamName, user.getId());
            for (UUID m : members) team.add(projectId, m, m.equals(rep) ? "REPRESENTATIVE" : "MEMBER", user.getId());
        }
        audit.record(user.getId(), "PROJECT_CREATED", "project", projectId.toString(),
            Map.of("classId", classId.toString(), "kind", kind));
        return viewOf(projectId, student ? ProjectRole.REPRESENTATIVE : ProjectRole.STAFF);
    }

    @Transactional
    public ProjectView update(AppUserDetails user, UUID projectId, UpdateProject req) {
        if (req.version() == null) throw bad("version is required");
        ProjectRole role = policy.requireProjectEdit(user, projectId);
        LockedProject lp = lockActive(projectId);
        role = policy.requireProjectEdit(user, projectId); // re-check: the role may have changed while we waited for the lock
        checkVersion(lp.project(), req.version());
        String title = req.title() == null ? lp.project().title() : text(req.title(), "title", 200, true);
        String description = req.description() == null ? lp.project().description() : text(req.description(), "description", 2000, false);
        String teamName = lp.project().teamName();
        if (req.teamName() != null) {
            if ("INDIVIDUAL".equals(lp.project().kind())) throw bad("An individual project has no team name");
            teamName = text(req.teamName(), "teamName", 100, true);
        }
        repo.updateDetails(projectId, title, description, teamName);
        audit.record(user.getId(), "PROJECT_UPDATED", "project", projectId.toString(), Map.of());
        return viewOf(projectId, role);
    }

    @Transactional
    public ProjectView archive(AppUserDetails user, UUID projectId) {
        ProjectRole role = policy.requireProjectStaff(user, projectId);
        UUID classId = repo.classIdOf(projectId).orElseThrow(AccessPolicy::projectNotFound);
        classes.lock(classId);
        ProjectRow p = repo.lock(projectId).orElseThrow(AccessPolicy::projectNotFound);
        role = policy.requireProjectStaff(user, projectId); // re-check under the locks
        if (!"ARCHIVED".equals(p.status())) {
            repo.setStatus(projectId, "ARCHIVED");
            repo.cancelAllPendingForProject(projectId);
            audit.record(user.getId(), "PROJECT_ARCHIVED", "project", projectId.toString(), Map.of());
        }
        return viewOf(projectId, role);
    }

    // ================= membership =================

    /** Staff: replace the member set / representative (assign, transfer, exceptions). */
    @Transactional
    public ProjectView setMembers(AppUserDetails user, UUID projectId, SetMembers req) {
        if (req.version() == null) throw bad("version is required");
        policy.requireProjectStaff(user, projectId);
        LockedProject lp = lockActive(projectId);
        policy.requireProjectStaff(user, projectId); // re-check under the locks
        ProjectRow p = lp.project();
        checkVersion(p, req.version());
        var cls = lp.cls();
        UUID classId = p.classId();
        boolean individual = "INDIVIDUAL".equals(p.kind());
        boolean exception = Boolean.TRUE.equals(req.allowSizeException());

        List<UUID> desired = distinct(req.memberIds());
        if (individual) {
            if (desired.size() != 1) throw bad("An individual project has exactly one student");
        } else {
            if (desired.isEmpty()) throw bad("memberIds must list at least one student");
            checkTeamSize(cls, desired.size(), exception);
        }
        for (UUID id : desired) assertEnrolledAndFree(classId, id, projectId);

        List<MemberRow> current = repo.currentMembers(projectId);
        Map<UUID, String> currentRole = new LinkedHashMap<>();
        current.forEach(m -> currentRole.put(m.userId(), m.role()));

        UUID rep = null;
        if (!individual) {
            if (req.representativeId() != null) {
                if (!desired.contains(req.representativeId())) throw bad("representativeId must be one of memberIds");
                rep = req.representativeId();
            } else {
                rep = currentRole.entrySet().stream()
                    .filter(e -> "REPRESENTATIVE".equals(e.getValue()) && desired.contains(e.getKey()))
                    .map(Map.Entry::getKey).findFirst().orElse(desired.get(0));
            }
        }

        // 1. removals, 2. demotions, 3. promotions, 4. additions (keeps "one representative" valid throughout)
        for (UUID existing : currentRole.keySet()) {
            if (!desired.contains(existing)) repo.endMember(projectId, existing, user.getId());
        }
        List<UUID> promotions = new ArrayList<>();
        List<UUID> additions = new ArrayList<>();
        for (UUID id : desired) {
            String wanted = id.equals(rep) ? "REPRESENTATIVE" : "MEMBER";
            String has = currentRole.get(id);
            if (has == null) additions.add(id);
            else if (!has.equals(wanted)) {
                if ("MEMBER".equals(wanted)) team.changeRole(projectId, id, "MEMBER", user.getId());
                else promotions.add(id);
            }
        }
        for (UUID id : promotions) team.changeRole(projectId, id, "REPRESENTATIVE", user.getId());
        for (UUID id : additions) {
            team.add(projectId, id, id.equals(rep) ? "REPRESENTATIVE" : "MEMBER", user.getId());
            repo.cancelPendingForUserInClass(classId, id);
        }
        repo.touch(projectId);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("before", currentRole.keySet().stream().map(UUID::toString).toList());
        details.put("after", desired.stream().map(UUID::toString).toList());
        details.put("sizeException", exception);
        audit.record(user.getId(), "PROJECT_MEMBERS_SET", "project", projectId.toString(), details);
        return viewOf(projectId, ProjectRole.STAFF);
    }

    /** Staff or the current representative: hand the representative role to another current member. */
    @Transactional
    public ProjectView setRepresentative(AppUserDetails user, UUID projectId, UUID studentId) {
        ProjectRole role = policy.requireProjectEdit(user, projectId);
        LockedProject lp = lockActive(projectId);
        role = policy.requireProjectEdit(user, projectId); // re-check under the locks
        if ("INDIVIDUAL".equals(lp.project().kind())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An individual project has no representative");
        }
        List<MemberRow> members = repo.currentMembers(projectId);
        if (members.stream().noneMatch(m -> m.userId().equals(studentId))) {
            throw bad("The new representative must be a current member of the team");
        }
        MemberRow old = members.stream().filter(m -> "REPRESENTATIVE".equals(m.role())).findFirst().orElse(null);
        if (old == null || !old.userId().equals(studentId)) {
            if (old != null) team.changeRole(projectId, old.userId(), "MEMBER", user.getId());
            team.changeRole(projectId, studentId, "REPRESENTATIVE", user.getId());
            repo.touch(projectId);
            audit.record(user.getId(), "PROJECT_REPRESENTATIVE_CHANGED", "project", projectId.toString(),
                Map.of("from", old == null ? "" : old.userId().toString(), "to", studentId.toString()));
        }
        return viewOf(projectId, role);
    }

    /** A student leaves their team, if the class policy allows team changes. */
    @Transactional
    public void leave(AppUserDetails user, UUID projectId) {
        ProjectRole role = policy.requireProjectView(user, projectId);
        if (role == ProjectRole.STAFF) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Staff manage membership with PUT /members");
        }
        LockedProject lp = lockActive(projectId);
        // Re-check under the locks: a concurrent removal must not produce a bogus "left" event.
        if (policy.requireProjectView(user, projectId) == ProjectRole.STAFF) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Staff manage membership with PUT /members");
        }
        if (!"TEAM".equals(lp.project().kind())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Individual projects cannot be left; ask your instructor");
        }
        if (!lp.cls().studentsMayChangeTeams()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your instructor does not allow students to change teams");
        }
        team.remove(projectId, user.getId(), user.getId(), "left team");
        audit.record(user.getId(), "PROJECT_MEMBER_LEFT", "project", projectId.toString(), Map.of());
    }

    // ================= invitations =================

    @Transactional
    public InvitationView invite(AppUserDetails user, UUID projectId, UUID studentId) {
        ProjectRole role = policy.requireProjectEdit(user, projectId);
        LockedProject lp = lockActive(projectId);
        role = policy.requireProjectEdit(user, projectId); // re-check: a representative removed while we waited must not invite
        if (!"TEAM".equals(lp.project().kind())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only team projects have invitations");
        }
        if (role == ProjectRole.REPRESENTATIVE && !"STUDENT_FORMED".equals(lp.cls().teamFormation())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Teams are assigned by your instructor in this class");
        }
        UUID classId = lp.project().classId();
        if (studentId.equals(user.getId())) throw bad("You cannot invite yourself");
        var student = classes.findStudentById(studentId).filter(s -> "STUDENT".equals(s.role()) && !"DEACTIVATED".equals(s.status()));
        var enrollment = classes.enrollmentStatus(classId, studentId);
        if (student.isEmpty() || enrollment.isEmpty() || !"ENROLLED".equals(enrollment.get())) {
            // Same answer for unknown ids and students of other classes: no roster probing.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Classmate not found");
        }
        if (repo.activeProjectOf(classId, studentId).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The student already belongs to a project in this class");
        }
        int taken = repo.currentMembers(projectId).size() + repo.livePendingCount(projectId);
        if (taken >= lp.cls().teamMaxSize()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The team is full (members plus pending invitations)");
        }
        UUID id = repo.insertInvitation(projectId, studentId, user.getId(), invitationDays);
        audit.record(user.getId(), "TEAM_INVITATION_SENT", "project", projectId.toString(),
            Map.of("invitationId", id.toString(), "studentId", studentId.toString()));
        return repo.pendingInvitations(projectId).stream().filter(i -> i.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public void cancelInvitation(AppUserDetails user, UUID projectId, UUID invitationId) {
        policy.requireProjectEdit(user, projectId);
        lockActive(projectId);                              // class -> project, like every other membership change
        policy.requireProjectEdit(user, projectId);         // re-check under the locks
        var inv = repo.findInvitation(invitationId, true).filter(i -> i.projectId().equals(projectId))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invitation not found"));
        if (!"PENDING".equals(inv.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The invitation is no longer pending");
        }
        repo.setInvitationStatus(invitationId, "CANCELLED");
        audit.record(user.getId(), "TEAM_INVITATION_CANCELLED", "project", projectId.toString(),
            Map.of("invitationId", invitationId.toString()));
    }

    @Transactional
    public ProjectView accept(AppUserDetails user, UUID invitationId) {
        // Lock order is always class -> project -> invitation (every staff path locks the class first and then
        // touches invitation rows). So: find the invitation without a lock, take the class and project locks,
        // and only then lock and re-read the invitation. Locking the invitation first could deadlock.
        UUID projectId = ownInvitation(user, invitationId, false).projectId();
        LockedProject lp = lockForInvitee(projectId);
        UUID classId = lp.project().classId();
        var inv = ownInvitation(user, invitationId, true);
        requireStillValid(inv);
        if (!"ACTIVE".equals(lp.project().status()) || !"TEAM".equals(lp.project().kind())
            || !"STUDENT_FORMED".equals(lp.cls().teamFormation())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This team is no longer accepting members");
        }
        var enrollment = classes.enrollmentStatus(classId, user.getId());
        if (enrollment.isEmpty() || !"ENROLLED".equals(enrollment.get())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You are no longer enrolled in this class");
        }
        if (repo.activeProjectOf(classId, user.getId()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You already belong to a project in this class");
        }
        if (repo.currentMembers(projectId).size() >= lp.cls().teamMaxSize()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The team is full");
        }
        team.add(projectId, user.getId(), "MEMBER", user.getId());
        repo.setInvitationStatus(invitationId, "ACCEPTED");
        repo.cancelPendingForUserInClass(classId, user.getId());
        repo.touch(projectId);
        audit.record(user.getId(), "TEAM_INVITATION_ACCEPTED", "project", projectId.toString(),
            Map.of("invitationId", invitationId.toString()));
        return viewOf(projectId, ProjectRole.MEMBER);
    }

    @Transactional
    public void decline(AppUserDetails user, UUID invitationId) {
        var inv = ownInvitation(user, invitationId, true); // takes no other lock, so no ordering issue
        requireStillValid(inv);
        repo.setInvitationStatus(invitationId, "DECLINED");
        audit.record(user.getId(), "TEAM_INVITATION_DECLINED", "project", inv.projectId().toString(),
            Map.of("invitationId", invitationId.toString()));
    }

    // ================= helpers =================

    private record LockedProject(ProjectRow project, LockedClass cls) {}

    /** Class row first, then project row; the project must be ACTIVE in an ACTIVE class. */
    private LockedProject lockActive(UUID projectId) {
        UUID classId = repo.classIdOf(projectId).orElseThrow(AccessPolicy::projectNotFound);
        var cls = lockWritableClass(classId);
        ProjectRow p = repo.lock(projectId).orElseThrow(AccessPolicy::projectNotFound);
        if (!"ACTIVE".equals(p.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The project is archived and read-only");
        }
        return new LockedProject(p, cls);
    }

    /** Like lockActive but without the status checks, so the caller can report precise reasons. */
    private LockedProject lockForInvitee(UUID projectId) {
        UUID classId = repo.classIdOf(projectId).orElseThrow(AccessPolicy::projectNotFound);
        var cls = classes.lock(classId).orElseThrow(AccessPolicy::classNotFound);
        ProjectRow p = repo.lock(projectId).orElseThrow(AccessPolicy::projectNotFound);
        if ("ARCHIVED".equals(cls.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The class is archived");
        }
        return new LockedProject(p, cls);
    }

    private LockedClass lockWritableClass(UUID classId) {
        var cls = classes.lock(classId).orElseThrow(AccessPolicy::classNotFound);
        if ("ARCHIVED".equals(cls.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The class is archived and read-only");
        }
        return cls;
    }

    /** The invitation must exist and be addressed to the caller; anyone else gets the same 404. */
    private InvitationRow ownInvitation(AppUserDetails user, UUID invitationId, boolean lock) {
        return repo.findInvitation(invitationId, lock).filter(i -> i.invitedUserId().equals(user.getId()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invitation not found"));
    }

    private static void requireStillValid(InvitationRow inv) {
        if (!"PENDING".equals(inv.status()) || inv.expiresAt().isBefore(java.time.OffsetDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The invitation is no longer valid");
        }
    }

    private void assertEnrolledAndFree(UUID classId, UUID studentId, UUID exceptProject) {
        var enrollment = classes.enrollmentStatus(classId, studentId);
        var student = classes.findStudentById(studentId);
        if (enrollment.isEmpty() || !"ENROLLED".equals(enrollment.get()) || student.isEmpty()
            || !"STUDENT".equals(student.get().role()) || "DEACTIVATED".equals(student.get().status())) {
            throw bad("Student " + studentId + " is not an active, enrolled student of this class");
        }
        var other = repo.activeProjectOf(classId, studentId);
        if (other.isPresent() && !other.get().equals(exceptProject)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Student " + studentId + " already belongs to another project in this class");
        }
    }

    private static void checkTeamSize(LockedClass cls, int size, boolean exception) {
        if (exception) {
            if (size < 1 || size > HARD_MAX_TEAM) throw bad("Team size must be between 1 and " + HARD_MAX_TEAM);
        } else if (size < cls.teamMinSize() || size > cls.teamMaxSize()) {
            throw bad("Team size must be between " + cls.teamMinSize() + " and " + cls.teamMaxSize()
                + " (set allowSizeException to override)");
        }
    }

    private static void checkVersion(ProjectRow p, long version) {
        if (p.version() != version) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The project was modified by someone else; reload and retry");
        }
    }

    private ProjectView viewOf(UUID projectId, ProjectRole role) {
        ProjectRow p = repo.find(projectId).orElseThrow(AccessPolicy::projectNotFound);
        var cls = classes.peek(p.classId()).orElseThrow(AccessPolicy::classNotFound);
        return build(p, role, cls, repo.currentMembers(projectId));
    }

    private ProjectView build(ProjectRow p, ProjectRole role, LockedClass cls, List<MemberRow> members) {
        boolean staff = role.isStaff();
        List<MemberView> memberViews = members.stream().map(m -> new MemberView(m.userId(), m.firstName(), m.lastName(),
            staff ? m.studentNumber() : null, m.role(), m.joinedAt())).toList();
        boolean meetsMin = "INDIVIDUAL".equals(p.kind()) ? members.size() == 1 : members.size() >= cls.teamMinSize();
        List<InvitationView> pending = role.canEdit() && "TEAM".equals(p.kind()) ? repo.pendingInvitations(p.id()) : List.of();
        return new ProjectView(p.id(), p.classId(), p.kind(), p.title(), p.description(), p.teamName(), p.status(),
            p.version(), p.createdAt(), role.name(), memberViews,
            new TeamPolicy(cls.teamFormation(), cls.teamMinSize(), cls.teamMaxSize(), cls.studentsMayChangeTeams()),
            meetsMin, pending);
    }

    private static List<UUID> distinct(List<UUID> ids) {
        return ids == null ? new ArrayList<>() : new ArrayList<>(new LinkedHashSet<>(ids));
    }

    private static String text(String value, String field, int max, boolean required) {
        String v = value == null || value.isBlank() ? null : value.trim();
        if (v == null) {
            if (required) throw bad(field + " is required");
            return null;
        }
        if (v.length() > max) throw bad(field + " is too long (max " + max + ")");
        return v;
    }

    private static ResponseStatusException bad(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
