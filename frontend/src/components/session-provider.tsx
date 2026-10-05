import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import { api } from '@/lib/api'
import type { Me } from '@/lib/auth'

interface Session {
  me: Me | null
  loading: boolean
  setMe: (me: Me | null) => void
  signOut: () => Promise<void>
}

const SessionContext = createContext<Session | null>(null)

export function SessionProvider({ children }: { children: React.ReactNode }) {
  const [me, setMe] = useState<Me | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    const ctrl = new AbortController()
    api('/api/me', { signal: ctrl.signal })
      .then((r) => (r.ok ? (r.json() as Promise<Me>) : null))
      .then(setMe)
      .catch(() => setMe(null))
      .finally(() => {
        if (!ctrl.signal.aborted) setLoading(false)
      })
    return () => ctrl.abort()
  }, [])

  const signOut = useCallback(async () => {
    await api('/logout', { method: 'POST' })
    setMe(null)
  }, [])

  const value = useMemo(() => ({ me, loading, setMe, signOut }), [me, loading, signOut])
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>
}

export function useSession() {
  const ctx = useContext(SessionContext)
  if (!ctx) throw new Error('useSession must be used within SessionProvider')
  return ctx
}
