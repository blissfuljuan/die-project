package com.blissfuljuan.dieapi.access;

import com.blissfuljuan.dieapi.access.model.ClassRole;
import com.blissfuljuan.dieapi.access.model.ProjectRole;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR 0002: administrators administer, but never read project/workspace content. */
class AdminContentAccessTest {

    @Test
    void administratorsCanAdministerButNotReadContent() {
        assertThat(ProjectRole.ADMIN.isStaff()).isTrue();
        assertThat(ProjectRole.ADMIN.canEdit()).isTrue();
        assertThat(ProjectRole.ADMIN.canAccessContent()).isFalse();
        assertThat(ClassRole.ADMIN.canManage()).isTrue();
        assertThat(ClassRole.ADMIN.canAccessContent()).isFalse();
    }

    @Test
    void onlyTeamMembersAndAssignedCoordinatorsReadProjectContent() {
        assertThat(ProjectRole.MEMBER.canAccessContent()).isTrue();
        assertThat(ProjectRole.REPRESENTATIVE.canAccessContent()).isTrue();
        assertThat(ProjectRole.STAFF.canAccessContent()).isTrue();
        assertThat(ProjectRole.NONE.canAccessContent()).isFalse();
        assertThat(ClassRole.COORDINATOR.canAccessContent()).isTrue();
        assertThat(ClassRole.PARTICIPANT.canAccessContent()).isTrue();
        assertThat(ClassRole.NONE.canAccessContent()).isFalse();
    }
}
