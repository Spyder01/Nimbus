import { Navigate } from 'react-router'
import { AuthButtons } from '@/components/auth-buttons'
import { Marquee } from '@/components/marquee'
import { Reveal } from '@/components/reveal'
import { Steps } from '@/components/steps'
import { Logo } from '@/components/logo'
import { StackPreview } from '@/components/stack-preview'
import { ThemeToggle } from '@/components/theme-toggle'
import { Splash } from '@/components/splash'
import { useSession } from '@/components/session-provider'
import { homeFor } from '@/lib/auth'
import { useTypewriter } from '@/lib/use-typewriter'

const PHRASES = ['a picture', 'a YAML file', 'a diagram', 'one click']

// Every band is full-width with its own divider; content is capped by .wrap.
const wrap = 'mx-auto max-w-7xl px-6 lg:px-10'

export default function Landing() {
  const word = useTypewriter(PHRASES)
  const { me, loading } = useSession()
  // Signed-in users never see the marketing page: profile first if it isn't done, else the dashboard.
  if (loading) return <Splash />
  if (me) return <Navigate to={homeFor(me)} replace />
  return (
    <div className="min-h-screen">
      {/* 1 — hero */}
      <div className="border-border relative overflow-hidden border-b">
        <div className="bg-glow pointer-events-none absolute inset-0 -z-10" />
        <div className="bg-dots pointer-events-none absolute inset-0 -z-10" />

        <header className={`${wrap} flex h-16 items-center justify-between`}>
          <Logo />
          <ThemeToggle />
        </header>

        <section className={`${wrap} grid items-center gap-12 py-10 lg:grid-cols-[minmax(0,30rem)_minmax(0,1fr)] lg:gap-16 lg:py-16 xl:gap-24`}>
          <div className="rise">
            <p className="bg-surface border-border text-muted-foreground mb-5 inline-flex items-center gap-2 rounded-full border px-3 py-1 text-xs font-medium backdrop-blur">
              <span className="bg-ok live-dot size-1.5 rounded-full" />
              Diagram in, cluster out
            </p>
            <h1
              aria-label="Ship your whole stack from a picture, a YAML file, a diagram or one click"
              className="text-4xl leading-[1.05] font-semibold tracking-tight text-balance sm:text-5xl lg:text-6xl"
            >
              Ship your whole stack from{' '}
              <span className="block min-h-[1.1em]">
                <span className="text-brand-gradient">{word}</span>
                <span className="caret" aria-hidden="true" />
              </span>
            </h1>
            <p className="text-muted-foreground mt-5 text-base text-pretty sm:text-lg lg:text-xl">
              Nimbus turns a diagram or a YAML of images into a running, scalable app on Kubernetes.
              Every component, wired up and ready to grow.
            </p>
            <div className="mt-8">
              <AuthButtons />
            </div>
            <p className="text-muted-foreground mt-4 text-xs">
              No passwords. New here? Signing in creates your account.
            </p>
          </div>

          <div className="rise [animation-delay:120ms]">
            <StackPreview />
          </div>
        </section>
      </div>

      {/* 2 — image marquee */}
      <section className="border-border bg-muted/40 border-b py-10">
        <Reveal>
          <p className="text-muted-foreground mb-5 text-center text-xs font-medium tracking-widest uppercase">
            Bring any container image
          </p>
          <Marquee />
        </Reveal>
      </section>

      {/* 3 — how it works */}
      <div className="border-border border-b">
        <div className={`${wrap} py-20 lg:py-28`}>
          <Steps />
        </div>
      </div>

      {/* 4 — closing call to action */}
      <section className="border-border bg-muted/40 relative overflow-hidden border-b">
        <div className="bg-glow pointer-events-none absolute inset-0 -z-0 opacity-70" />
        <Reveal className={`${wrap} relative py-20 text-center lg:py-28`}>
          <h2 className="text-3xl font-light tracking-tight text-balance sm:text-5xl">
            Your stack is one sign-in away.
          </h2>
          <p className="text-muted-foreground mx-auto mt-4 max-w-md text-pretty">
            Connect an account and deploy your first app in minutes.
          </p>
          <div className="mx-auto mt-8 max-w-sm">
            <AuthButtons />
          </div>
        </Reveal>
      </section>

      <footer className={`${wrap} text-muted-foreground flex items-center justify-between py-8 text-xs`}>
        <Logo />
        <span>© {new Date().getFullYear()} Nimbus</span>
      </footer>
    </div>
  )
}
