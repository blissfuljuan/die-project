import { PageHeader } from '@/components/common/PageHeader'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { ChangePasswordForm } from '@/features/auth/ChangePasswordForm'
import { useSession } from '@/features/session/useSession'
import { fullName, ROLE_LABEL } from '@/lib/roles'

export function ProfilePage() {
  const { user } = useSession()
  if (!user) return null
  return (
    <>
      <PageHeader title="My profile" />
      <div className="flex flex-col gap-6">
        <Card>
          <CardHeader>
            <CardTitle>Account</CardTitle>
          </CardHeader>
          <CardContent>
            <dl className="grid grid-cols-1 gap-x-6 gap-y-3 text-sm sm:grid-cols-3">
              <div>
                <dt className="text-muted-foreground">Name</dt>
                <dd className="font-medium break-words">{fullName(user)}</dd>
              </div>
              <div>
                <dt className="text-muted-foreground">Email</dt>
                <dd className="font-medium break-all">{user.email}</dd>
              </div>
              <div>
                <dt className="text-muted-foreground">Role</dt>
                <dd>
                  <Badge variant="secondary">{ROLE_LABEL[user.role]}</Badge>
                </dd>
              </div>
            </dl>
          </CardContent>
        </Card>
        <Card id="password">
          <CardHeader>
            <CardTitle>Change password</CardTitle>
            <CardDescription>Changing your password signs you out on your other devices.</CardDescription>
          </CardHeader>
          <CardContent>
            <ChangePasswordForm />
          </CardContent>
        </Card>
      </div>
    </>
  )
}
