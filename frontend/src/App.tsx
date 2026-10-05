import { BrowserRouter, Navigate, Route, Routes } from 'react-router'
import { SessionProvider } from '@/components/session-provider'
import { ThemeProvider } from '@/components/theme-provider'
import { AppShell } from '@/components/app-shell'
import Dashboard from '@/pages/dashboard'
import Landing from '@/pages/landing'
import Profile from '@/pages/profile'
import Settings from '@/pages/settings'

export default function App() {
  return (
    <ThemeProvider>
      <SessionProvider>
        <BrowserRouter>
          <Routes>
            <Route path="/" element={<Landing />} />
            <Route path="/profile" element={<Profile />} />
            <Route element={<AppShell />}>
              <Route path="/dashboard" element={<Dashboard />} />
              <Route path="/settings" element={<Settings />} />
            </Route>
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </BrowserRouter>
      </SessionProvider>
    </ThemeProvider>
  )
}
