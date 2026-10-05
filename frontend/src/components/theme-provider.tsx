import { createContext, useCallback, useContext, useEffect, useState } from 'react'

type Theme = 'light' | 'dark'
const KEY = 'nimbus-theme'

const ThemeContext = createContext<{ theme: Theme; toggle: () => void; setTheme: (t: Theme) => void } | null>(null)

function initialTheme(): Theme {
  return document.documentElement.classList.contains('dark') ? 'dark' : 'light'
}

export function ThemeProvider({ children }: { children: React.ReactNode }) {
  const [theme, setTheme] = useState<Theme>(initialTheme)

  useEffect(() => {
    document.documentElement.classList.toggle('dark', theme === 'dark')
  }, [theme])

  // Follow the OS until the user picks a theme explicitly.
  useEffect(() => {
    const mq = matchMedia('(prefers-color-scheme: dark)')
    const onChange = (e: MediaQueryListEvent) => {
      try {
        if (!localStorage.getItem(KEY)) setTheme(e.matches ? 'dark' : 'light')
      } catch {
        /* storage unavailable */
      }
    }
    mq.addEventListener('change', onChange)
    return () => mq.removeEventListener('change', onChange)
  }, [])

  const toggle = useCallback(() => {
    setTheme((t) => {
      const next = t === 'dark' ? 'light' : 'dark'
      try {
        localStorage.setItem(KEY, next)
      } catch {
        /* storage unavailable */
      }
      return next
    })
  }, [])

  const choose = useCallback((t: Theme) => {
    try {
      localStorage.setItem(KEY, t)
    } catch {
      /* storage unavailable */
    }
    setTheme(t)
  }, [])

  return <ThemeContext.Provider value={{ theme, toggle, setTheme: choose }}>{children}</ThemeContext.Provider>
}

export function useTheme() {
  const ctx = useContext(ThemeContext)
  if (!ctx) throw new Error('useTheme must be used within ThemeProvider')
  return ctx
}
