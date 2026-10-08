import { ArrowRight, Boxes, FileCode2, GitBranch, HardDrive, TrendingUp } from 'lucide-react'
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
import { Button } from '@/components/ui/button'
import { homeFor, startOAuth } from '@/lib/auth'
import { useTypewriter } from '@/lib/use-typewriter'

const PHRASES = ['a picture', 'a YAML file', 'a diagram', 'one click']

// Every band is full-width with its own divider; content is capped by .wrap.
const wrap = 'mx-auto max-w-7xl px-6 lg:px-10'

// What the product does, in a line each; shown under the sign-in buttons.
const FEATURES = [
  { icon: GitBranch, label: 'Deploys in dependency order' },
  { icon: TrendingUp, label: 'Autoscaling' },
  { icon: HardDrive, label: 'Persistent volumes' },
  { icon: FileCode2, label: 'YAML in, YAML out' },
]

export default function Landing() {
  const word = useTypewriter(PHRASES)
  const { me, loading } = useSession()
  // Signed-in users never see the marketing page: profile first if it isn't done, else the dashboard.
  if (loading) return <Splash />
  if (me) return <Navigate to={homeFor(me)} replace />
  return (
    <div className="min-h-screen">
      <header className="border-border bg-background/70 sticky top-0 z-30 border-b backdrop-blur-md">
        <div className={`${wrap} flex h-16 items-center justify-between`}>
          <Logo />
          <div className="flex items-center gap-1.5">
            <a
              href="#how-it-works"
              className="text-muted-foreground hover:text-foreground hidden rounded-lg px-3 py-2 text-sm font-medium transition-colors sm:block"
            >
              How it works
            </a>
            <ThemeToggle />
            <Button size="sm" className="ml-1" onClick={() => startOAuth('github')}>
              Sign in
            </Button>
          </div>
        </div>
      </header>

      {/* 1 — hero */}
      <div className="border-border relative overflow-hidden border-b">
        <div className="bg-glow pointer-events-none absolute inset-0 -z-10" />
        <div className="bg-dots pointer-events-none absolute inset-0 -z-10" />

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
            <ul className="mt-8 grid max-w-md grid-cols-2 gap-x-6 gap-y-3 text-sm">
              {FEATURES.map(({ icon: Icon, label }) => (
                <li key={label} className="text-muted-foreground flex items-center gap-2">
                  <span className="bg-info/12 text-info grid size-6 shrink-0 place-items-center rounded-md">
                    <Icon className="size-3.5" />
                  </span>
                  {label}
                </li>
              ))}
            </ul>
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
      <section className={`${wrap} py-20 lg:py-28`}>
        <Reveal className="border-border bg-card relative overflow-hidden rounded-3xl border px-6 py-16 text-center shadow-[var(--shadow-card)] sm:px-12 lg:py-20">
          <div className="bg-glow pointer-events-none absolute inset-0 opacity-80" />
          <div className="relative">
            <span className="bg-brand-gradient mx-auto mb-6 grid size-11 place-items-center rounded-xl text-white">
              <Boxes className="size-5" />
            </span>
            <h2 className="text-3xl font-light tracking-tight text-balance sm:text-5xl">Your stack is one sign-in away.</h2>
            <p className="text-muted-foreground mx-auto mt-4 max-w-md text-pretty">
              Connect an account and deploy your first app in minutes.
            </p>
            <div className="mx-auto mt-8 max-w-sm">
              <AuthButtons />
            </div>
            <a href="#how-it-works" className="text-muted-foreground hover:text-foreground mt-6 inline-flex items-center gap-1 text-sm transition-colors">
              See how it works <ArrowRight className="size-3.5" />
            </a>
          </div>
        </Reveal>
      </section>

      <footer className={`${wrap} text-muted-foreground border-border flex items-center justify-between border-t py-8 text-xs`}>
        <Logo />
        <span>© {new Date().getFullYear()} Nimbus</span>
      </footer>
    </div>
  )
}
