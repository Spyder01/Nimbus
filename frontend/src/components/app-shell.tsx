import { useEffect, useState } from 'react'
import { LayoutDashboard, LogOut, PanelLeftClose, PanelLeftOpen, Server, Settings, Users } from 'lucide-react'
import { NavLink, Navigate, Outlet, useMatch } from 'react-router'
import { Avatar } from '@/components/avatar'
import { Logo } from '@/components/logo'
import { Splash } from '@/components/splash'
import { ThemeToggle } from '@/components/theme-toggle'
import { Button } from '@/components/ui/button'
import { useSession } from '@/components/session-provider'
import { homeFor, isAdmin as hasAdmin } from '@/lib/auth'
import { cn } from '@/lib/utils'

const NAV = [
  { to: '/dashboard', label: 'Dashboard', icon: LayoutDashboard },
  { to: '/settings', label: 'Settings', icon: Settings },
]

const ADMIN_NAV = [
  { to: '/admin/workers', label: 'Workers', icon: Server },
  { to: '/admin/members', label: 'Members', icon: Users },
]

const KEY = 'nimbus-nav-collapsed'

function useCollapsed() {
  const [collapsed, setCollapsed] = useState(() => {
    try {
      return localStorage.getItem(KEY) === '1'
    } catch {
      return false
    }
  })
  useEffect(() => {
    try {
      localStorage.setItem(KEY, collapsed ? '1' : '0')
    } catch {
      /* storage unavailable */
    }
  }, [collapsed])
  return [collapsed, setCollapsed] as const
}

const linkClass = ({ isActive }: { isActive: boolean }) =>
  cn(
    'flex items-center gap-2.5 rounded-lg px-3 py-2 text-sm font-medium whitespace-nowrap transition-colors',
    isActive ? 'nav-active bg-sidebar-accent text-foreground' : 'text-muted-foreground hover:bg-sidebar-accent/60 hover:text-foreground',
  )

// Layout route for everything behind login + onboarding.
export function AppShell() {
  const { me, loading, signOut } = useSession()
  const [collapsed, setCollapsed] = useCollapsed()
  const onCanvas = !!useMatch('/apps/:id')
  if (loading) return <Splash />
  if (!me || !me.profileUpdated) return <Navigate to={homeFor(me)} replace />
  const isAdmin = hasAdmin(me)

  return (
    <div className="flex min-h-screen flex-col md:flex-row">
      {/* desktop sidebar: collapses to an icon rail */}
      <aside
        className={cn(
          'border-border bg-sidebar sticky top-0 hidden h-screen shrink-0 flex-col overflow-hidden border-r p-3 transition-[width] duration-200 ease-out md:flex',
          collapsed ? 'w-[4.25rem]' : 'w-60',
        )}
      >
        <div className={cn('flex items-center', collapsed ? 'flex-col gap-3' : 'justify-between px-1.5 py-1')}>
          <Logo iconOnly={collapsed} />
          <Button
            variant="ghost"
            size="icon-sm"
            onClick={() => setCollapsed((c) => !c)}
            aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
            aria-expanded={!collapsed}
            title={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
          >
            {collapsed ? <PanelLeftOpen /> : <PanelLeftClose />}
          </Button>
        </div>

        <nav className="mt-6 flex flex-col gap-1" aria-label="Main">
          {NAV.map(({ to, label, icon: Icon }) => (
            <NavLink
              key={to}
              to={to}
              title={collapsed ? label : undefined}
              className={(s) => cn(linkClass(s), collapsed && 'justify-center px-0')}
            >
              <Icon className="size-4 shrink-0" />
              <span className={collapsed ? 'sr-only' : undefined}>{label}</span>
            </NavLink>
          ))}
          {isAdmin && (
            <>
              <div className={cn('text-muted-foreground mt-4 mb-1 px-3 text-[10px] font-medium tracking-widest uppercase', collapsed && 'sr-only')}>
                Admin
              </div>
              {ADMIN_NAV.map(({ to, label, icon: Icon }) => (
                <NavLink
                  key={to}
                  to={to}
                  title={collapsed ? label : undefined}
                  className={(s) => cn(linkClass(s), collapsed && 'justify-center px-0')}
                >
                  <Icon className="size-4 shrink-0" />
                  <span className={collapsed ? 'sr-only' : undefined}>{label}</span>
                </NavLink>
              ))}
            </>
          )}
        </nav>

        <div className="mt-auto space-y-2">
          <div
            className={cn(
              'border-border flex items-center rounded-lg border',
              collapsed ? 'flex-col gap-2 p-1.5' : 'gap-2.5 p-2.5',
            )}
            title={collapsed ? (me.name ?? undefined) : undefined}
          >
            <Avatar src={me.avatarUrl} name={me.name} className="size-8 shrink-0 text-sm" />
            {!collapsed && (
              <div className="min-w-0 flex-1">
                <div className="truncate text-sm font-medium">{me.name}</div>
                <div className="text-muted-foreground truncate text-xs">{me.email ?? 'No email'}</div>
              </div>
            )}
            <ThemeToggle />
          </div>
          <Button
            variant="ghost"
            size="sm"
            className={cn('w-full', collapsed ? 'justify-center px-0' : 'justify-start')}
            onClick={signOut}
            title={collapsed ? 'Sign out' : undefined}
          >
            <LogOut />
            <span className={collapsed ? 'sr-only' : undefined}>Sign out</span>
          </Button>
        </div>
      </aside>

      {/* mobile top bar */}
      <header className="border-border bg-background/80 sticky top-0 z-10 border-b backdrop-blur md:hidden">
        <div className="flex h-14 items-center justify-between px-4">
          <Logo />
          <ThemeToggle />
        </div>
        <nav className="flex gap-1 px-3 pb-2" aria-label="Main">
          {[...NAV, ...(isAdmin ? ADMIN_NAV : [])].map(({ to, label, icon: Icon }) => (
            <NavLink key={to} to={to} className={linkClass}>
              <Icon className="size-4" />
              {label}
            </NavLink>
          ))}
        </nav>
      </header>

      <main className={cn('min-w-0 flex-1', !onCanvas && 'px-5 py-8 md:px-10 md:py-10')}>
        <Outlet />
      </main>
    </div>
  )
}
