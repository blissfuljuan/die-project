package com.blissfuljuan.dieapi.academic.service.port;

import java.util.UUID;

/**
 * Port (Observer) for modules that own data tied to a student's enrollment. Called synchronously by
 * ClassEnrollmentService.unenroll inside its transaction, after the class row is locked and the enrollment is
 * marked removed, so every handler sees the same transaction and the class-first lock order. Implemented outside
 * the academic module (the project module ends team membership and cancels invitations), which keeps academic
 * free of a dependency on those modules.
 */
public interface StudentRemovalHandler {

    void onStudentRemovedFromClass(UUID workspaceId, UUID participantId, UUID actor);
}
