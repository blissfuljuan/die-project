import { useEffect, useRef, useState } from 'react'
import { Link, NavLink, Outlet, useNavigate } from 'react-router-dom'
import type { Me } from '@/api'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { useLogout, useSession } from '@/features/session/useSession'
import { cn } from '@/lib/utils'
import { fullName, homePathFor, NAV_BY_ROLE, ROLE_LABEL, type NavItem } from '@/lib/roles'

function Icon({ d }: { d: string }) {
  return (
    <svg aria-hidden="true" viewBox="0 0 24 24" className="size-5" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
      <path d={d} />
    </svg>
  )
}

function NavList({ items, onNavigate }: { items: NavItem[]; onNavigate?: () => void }) {
  return (
    <ul className="flex flex-col gap-1">
      {items.map((item) => (
        <li key={item.to}>
          <NavLink
            to={item.to}
            end={item.end}
            onClick={onNavigate}
            className={({ isActive }) =>
              cn(
                'flex min-h-10 items-center justify-between gap-2 rounded-md px-3 py-2 text-sm font-medium hover:bg-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
                isActive && 'bg-accent text-accent-foreground',
              )
            }
          >
            <span>{item.label}</span>
            {item.soon && <Badge variant="outline">Coming soon</Badge>}
          </NavLink>
        </li>
      ))}
    </ul>
  )
}

function UserMenu({ user }: { user: Me }) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)
  const navigate = useNavigate()
  const logout = useLogout()

  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  return (
    <div ref={ref} className="relative">
      <Button
        type="button"
        variant="ghost"
        aria-label={`Account menu for ${fullName(user)}`}
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
        className="max-w-[12rem] gap-2 sm:max-w-none"
      >
        <span className="truncate">{fullName(user)}</span>
        <Badge variant="secondary" className="hidden sm:inline-flex">
          {ROLE_LABEL[user.role]}
        </Badge>
      </Button>
      {open && (
        <div
          role="menu"
          aria-label="Account"
          className="absolute right-0 z-30 mt-1 w-60 rounded-md border bg-card p-1 shadow-md"
        >
          <div className="px-3 py-2 text-sm">
            <p className="font-medium break-words">{fullName(user)}</p>
            <p className="text-xs break-all text-muted-foreground">{user.email}</p>
            <Badge variant="secondary" className="mt-1">
              {ROLE_LABEL[user.role]}
            </Badge>
          </div>
          <button
            role="menuitem"
            type="button"
            className="flex min-h-10 w-full items-center rounded-md px-3 text-left text-sm hover:bg-accent"
            onClick={() => {
              setOpen(false)
              navigate('/me')
            }}
          >
            Change password
          </button>
          <button
            role="menuitem"
            type="button"
            disabled={logout.isPending}
            className="flex min-h-10 w-full items-center rounded-md px-3 text-left text-sm hover:bg-accent disabled:opacity-60"
            onClick={() => logout.mutate()}
          >
            {logout.isPending ? 'Signing out...' : 'Sign out'}
          </button>
        </div>
      )}
    </div>
  )
}

/** Sidebar on desktop, a drawer on phones. Navigation is role-aware but is not a security boundary. */
export function AppShell() {
  const { user } = useSession()
  const [drawer, setDrawer] = useState(false)
  const drawerRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!drawer) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setDrawer(false)
    }
    document.addEventListener('keydown', onKey)
    drawerRef.current?.querySelector<HTMLElement>('a,button')?.focus()
    return () => document.removeEventListener('keydown', onKey)
  }, [drawer])

  if (!user) return null
  const items = NAV_BY_ROLE[user.role]

  const sidebar = (onNavigate?: () => void) => (
    <nav aria-label="Main" className="flex h-full flex-col justify-between gap-6">
      <NavList items={items} onNavigate={onNavigate} />
      <Link to="/status" onClick={onNavigate} className="px-3 text-xs text-muted-foreground underline underline-offset-2">
        System status
      </Link>
    </nav>
  )

  return (
    <div className="min-h-screen">
      <a href="#main" className="sr-only focus:not-sr-only focus:absolute focus:z-50 focus:bg-card focus:p-2">
        Skip to content
      </a>
      <header className="sticky top-0 z-20 flex h-14 items-center justify-between gap-2 border-b bg-card px-4">
        <div className="flex items-center gap-2">
          <Button
            type="button"
            variant="ghost"
            size="sm"
            className="md:hidden"
            aria-label="Open menu"
            aria-expanded={drawer}
            aria-controls="mobile-drawer"
            onClick={() => setDrawer(true)}
          >
            <Icon d="M4 6h16M4 12h16M4 18h16" />
          </Button>
          <Link to={homePathFor(user.role)} className="text-base font-bold tracking-tight">
            DIE
          </Link>
        </div>
        <UserMenu user={user} />
      </header>

      <div className="md:flex">
        <aside className="sticky top-14 hidden h-[calc(100vh-3.5rem)] w-60 shrink-0 border-r bg-card p-3 md:block">
          {sidebar()}
        </aside>

        {drawer && (
          <div className="fixed inset-0 z-40 md:hidden">
            <div className="absolute inset-0 bg-black/40" onClick={() => setDrawer(false)} aria-hidden="true" />
            <div
              id="mobile-drawer"
              ref={drawerRef}
              role="dialog"
              aria-modal="true"
              aria-label="Navigation"
              className="absolute inset-y-0 left-0 flex w-72 max-w-[85vw] flex-col gap-4 bg-card p-3 shadow-lg"
            >
              <Button type="button" variant="ghost" size="sm" className="self-end" aria-label="Close menu" onClick={() => setDrawer(false)}>
                <Icon d="M6 6l12 12M18 6L6 18" />
              </Button>
              {sidebar(() => setDrawer(false))}
            </div>
          </div>
        )}

        <main id="main" className="min-w-0 flex-1 px-4 py-6 md:px-8">
          <div className="mx-auto w-full max-w-5xl">
            <Outlet />
          </div>
        </main>
      </div>
    </div>
  )
}
