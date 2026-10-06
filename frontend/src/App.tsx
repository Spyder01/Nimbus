import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { lazy, Suspense } from 'react'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router'
import { SessionProvider } from '@/components/session-provider'
import { ThemeProvider } from '@/components/theme-provider'
import { AppShell } from '@/components/app-shell'
import Dashboard from '@/pages/dashboard'
import Landing from '@/pages/landing'
import Profile from '@/pages/profile'
import Settings from '@/pages/settings'
import { Splash } from '@/components/splash'

// React Flow is heavy; only load it when a canvas is opened.
const AppCanvas = lazy(() => import('@/pages/app-canvas'))

// Server state (apps, versions). Failed requests aren't retried: errors are shown to the user instead.
const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: 15_000 } } })

export default function App() {
  return (
    <ThemeProvider>
      <QueryClientProvider client={queryClient}>
      <SessionProvider>
        <BrowserRouter>
          <Routes>
            <Route path="/" element={<Landing />} />
            <Route path="/profile" element={<Profile />} />
            <Route element={<AppShell />}>
              <Route path="/dashboard" element={<Dashboard />} />
              <Route path="/apps/:id" element={<Suspense fallback={<Splash />}><AppCanvas /></Suspense>} />
              <Route path="/settings" element={<Settings />} />
            </Route>
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </BrowserRouter>
      </SessionProvider>
      </QueryClientProvider>
    </ThemeProvider>
  )
}
