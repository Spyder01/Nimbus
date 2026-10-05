export type Provider = 'github' | 'google'

// Providers the backend has a client registration for. Google is not wired up yet.
export const ENABLED_PROVIDERS: Provider[] = ['github']

// Starts Spring Security's OAuth2 authorization-code flow (full-page redirect to the provider).
export function startOAuth(provider: Provider) {
  window.location.assign(`/oauth2/authorization/${provider}`)
}

export interface Me {
  id: string
  name: string | null
  email: string | null
  avatarUrl: string | null
  // false until the user has saved their profile once (first-login onboarding)
  profileUpdated: boolean
  role: 'USER' | 'ADMIN'
}

// Where a user belongs right now.
export function homeFor(me: Me | null): string {
  if (!me) return '/'
  return me.profileUpdated ? '/dashboard' : '/profile'
}
