import { ArrowRight, Loader2 } from 'lucide-react'
import { Navigate } from 'react-router'
import { Button } from '@/components/ui/button'
import { ProfileFields, useProfileForm } from '@/components/profile-form'
import { Avatar } from '@/components/avatar'
import { Logo } from '@/components/logo'
import { Splash } from '@/components/splash'
import { ThemeToggle } from '@/components/theme-toggle'
import { useSession } from '@/components/session-provider'
import { homeFor, type Me } from '@/lib/auth'

export default function ProfilePage() {
  const { me, loading, setMe } = useSession()
  if (loading) return <Splash />
  // Signed out, or profile already done: this page isn't for you (saving flips profileUpdated,
  // which is also what sends the user on to the dashboard).
  if (!me || me.profileUpdated) return <Navigate to={homeFor(me)} replace />
  return <ProfileForm me={me} onSaved={setMe} />
}

function ProfileForm({ me, onSaved }: { me: Me; onSaved: (me: Me) => void }) {
  const form = useProfileForm(me, onSaved)

  return (
    <div className="relative flex min-h-screen flex-col overflow-hidden">
      <div className="bg-glow pointer-events-none absolute inset-0 -z-10" />
      <div className="bg-dots pointer-events-none absolute inset-0 -z-10" />

      <header className="mx-auto flex h-16 w-full max-w-7xl items-center justify-between px-6 lg:px-10">
        <Logo />
        <ThemeToggle />
      </header>

      <main className="grid flex-1 place-items-center px-5 pb-16">
        <form
          onSubmit={form.submit}
          noValidate
          className="border-border bg-surface rise w-full max-w-md rounded-2xl border p-7 shadow-xl shadow-black/5 backdrop-blur-xl sm:p-9 dark:shadow-black/40"
        >
          <div className="mb-7 flex flex-col items-center text-center">
            <div className="bg-brand-gradient mb-5 rounded-full p-[3px]">
              <Avatar src={me.avatarUrl} name={form.name} className="border-background size-20 border-4 text-2xl" />
            </div>
            <h1 className="text-2xl font-semibold tracking-tight">Welcome to Nimbus</h1>
            <p className="text-muted-foreground mt-1.5 text-sm text-pretty">
              Confirm a few details so your workspace is ready.
            </p>
          </div>

          <ProfileFields form={form} />

          {form.failed && (
            <p role="alert" className="text-destructive mt-5 text-sm">
              We couldn't save your profile. Please try again.
            </p>
          )}

          <Button type="submit" size="lg" className="sheen mt-7 h-11 w-full text-sm" disabled={form.saving}>
            {form.saving ? <Loader2 className="animate-spin" /> : null}
            {form.saving ? 'Saving…' : 'Continue to dashboard'}
            {!form.saving && <ArrowRight className="ml-1" />}
          </Button>
        </form>
      </main>
    </div>
  )
}
