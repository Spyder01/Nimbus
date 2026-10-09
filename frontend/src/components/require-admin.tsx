import { Navigate, Outlet } from 'react-router'
import { useSession } from '@/components/session-provider'
import { homeFor, isAdmin } from '@/lib/auth'

// Admin pages are for admins only; everyone else is sent to their normal home.
export function RequireAdmin() {
  const { me } = useSession()
  if (!isAdmin(me)) return <Navigate to={homeFor(me)} replace />
  return <Outlet />
}
