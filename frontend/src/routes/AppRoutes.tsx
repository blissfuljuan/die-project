import { Route, Routes } from 'react-router-dom'
import { ActivatePage } from '@/features/auth/ActivatePage'
import { ForgotPasswordPage } from '@/features/auth/ForgotPasswordPage'
import { LoginPage } from '@/features/auth/LoginPage'
import { ResetPasswordPage } from '@/features/auth/ResetPasswordPage'
import { VerifyEmailPage } from '@/features/auth/VerifyEmailPage'
import { AdminDashboard } from '@/features/admin/AdminDashboard'
import { CoordinatorDashboard } from '@/features/dashboard/CoordinatorDashboard'
import { ParticipantDashboard } from '@/features/dashboard/ParticipantDashboard'
import { ProjectDetailPage } from '@/features/projects/ProjectDetailPage'
import { RosterImportPage } from '@/features/roster/RosterImportPage'
import { RosterPage } from '@/features/roster/RosterPage'
import { AdminWorkspacesPage } from '@/features/workspaces/AdminWorkspacesPage'
import { CreateWorkspacePage } from '@/features/workspaces/CreateWorkspacePage'
import { EditWorkspacePage } from '@/features/workspaces/EditWorkspacePage'
import { WorkspaceDetailPage } from '@/features/workspaces/WorkspaceDetailPage'
import { AppShell } from '@/layouts/AppShell'
import { ComingSoonPage } from '@/pages/ComingSoonPage'
import { NotFoundPage } from '@/pages/NotFoundPage'
import { ProfilePage } from '@/pages/ProfilePage'
import { StatusPage } from '@/pages/StatusPage'
import { PublicOnly, RequireAuth, RequireRole, RoleHomeRedirect } from './guards'

/**
 * Route map (see frontend/README.md). One area per role: /app (Participant), /coordinator, /admin; shared pages
 * /workspaces/:id, /projects/:id and /me. Guards are presentation only; the API enforces every permission.
 */
export function AppRoutes() {
  return (
    <Routes>
      <Route element={<PublicOnly />}>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/activate" element={<ActivatePage />} />
        <Route path="/forgot-password" element={<ForgotPasswordPage />} />
      </Route>
      {/* Emailed links: usable whether or not someone is signed in. */}
      <Route path="/verify-email" element={<VerifyEmailPage />} />
      <Route path="/reset-password" element={<ResetPasswordPage />} />
      <Route path="/status" element={<StatusPage />} />

      <Route element={<RequireAuth />}>
        <Route element={<AppShell />}>
          <Route path="/" element={<RoleHomeRedirect />} />
          <Route path="/me" element={<ProfilePage />} />
          <Route element={<RequireRole roles={['COORDINATOR', 'ADMIN']} />}>
            <Route path="/workspaces/new" element={<CreateWorkspacePage />} />
            <Route path="/workspaces/:workspaceId/edit" element={<EditWorkspacePage />} />
            <Route path="/workspaces/:workspaceId/roster" element={<RosterPage />} />
            <Route path="/workspaces/:workspaceId/roster/import" element={<RosterImportPage />} />
          </Route>
          <Route path="/workspaces/:workspaceId" element={<WorkspaceDetailPage />} />
          <Route path="/projects/:projectId" element={<ProjectDetailPage />} />

          <Route element={<RequireRole roles={['PARTICIPANT']} />}>
            <Route path="/app" element={<ParticipantDashboard />} />
          </Route>
          <Route element={<RequireRole roles={['COORDINATOR']} />}>
            <Route path="/coordinator" element={<CoordinatorDashboard />} />
          </Route>
          <Route element={<RequireRole roles={['ADMIN']} />}>
            <Route path="/admin" element={<AdminDashboard />} />
            <Route path="/admin/accounts" element={<ComingSoonPage title="Accounts" />} />
            <Route path="/admin/workspaces" element={<AdminWorkspacesPage />} />
          </Route>
        </Route>
      </Route>

      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  )
}
