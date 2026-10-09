export type Provider = 'github' | 'google'

// Providers the backend has a client registration for. Google is not wired up yet.
export const ENABLED_PROVIDERS: Provider[] = ['github']

// Starts Spring Security's OAuth2 authorization-code flow (full-page redirect to the provider).
export function startOAuth(provider: Provider) {
  window.location.assign(`/oauth2/authorization/${provider}`)
}

/** Each role can do what the ones before it can: a super admin is also an admin. */
export type Role = 'USER' | 'ADMIN' | 'SUPER_ADMIN'

export const isAdmin = (me: Me | null) => me?.role === 'ADMIN' || me?.role === 'SUPER_ADMIN'
export const isSuperAdmin = (me: Me | null) => me?.role === 'SUPER_ADMIN'

export const ROLE_LABEL: Record<Role, string> = { USER: 'Member', ADMIN: 'Admin', SUPER_ADMIN: 'Super admin' }

export interface Me {
  id: string
  name: string | null
  email: string | null
  avatarUrl: string | null
  // false until the user has saved their profile once (first-login onboarding)
  profileUpdated: boolean
  role: Role
}

// Where a user belongs right now.
export function homeFor(me: Me | null): string {
  if (!me) return '/'
  return me.profileUpdated ? '/dashboard' : '/profile'
}
