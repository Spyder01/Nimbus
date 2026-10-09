import { Check, Loader2, LogOut, Moon, Sun } from 'lucide-react'
import { Avatar } from '@/components/avatar'
import { ProfileFields, useProfileForm } from '@/components/profile-form'
import { useSession } from '@/components/session-provider'
import { useTheme } from '@/components/theme-provider'
import { Button } from '@/components/ui/button'
import { ROLE_LABEL, type Me } from '@/lib/auth'
import { cn } from '@/lib/utils'

export default function SettingsPage() {
  const { me, setMe, signOut } = useSession()
  // AppShell guarantees a signed-in user with a completed profile.
  if (!me) return null

  return (
    <div className="mx-auto max-w-2xl">
      <h1 className="text-2xl font-semibold tracking-tight">Settings</h1>
      <p className="text-muted-foreground mt-1 text-sm">Manage your profile and how Nimbus looks for you.</p>

      <div className="mt-8 space-y-6">
        <ProfileSection me={me} onSaved={setMe} />
        <AppearanceSection />

        <Section title="Account" description="You're signed in with a connected provider.">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="text-muted-foreground text-sm">
              Role: <span className="text-foreground font-medium">{ROLE_LABEL[me.role]}</span>
            </div>
            <Button variant="outline" onClick={signOut}>
              <LogOut /> Sign out
            </Button>
          </div>
        </Section>
      </div>
    </div>
  )
}

function Section({ title, description, children }: { title: string; description: string; children: React.ReactNode }) {
  return (
    <section className="border-border bg-card rounded-2xl border p-6">
      <h2 className="font-medium">{title}</h2>
      <p className="text-muted-foreground mt-0.5 mb-5 text-sm">{description}</p>
      {children}
    </section>
  )
}

function ProfileSection({ me, onSaved }: { me: Me; onSaved: (me: Me) => void }) {
  const form = useProfileForm(me, onSaved, { editAvatar: true })
  return (
    <Section title="Profile" description="Your name and contact details.">
      <form onSubmit={form.submit} noValidate>
        <div className="mb-6 flex items-center gap-4">
          <Avatar
            src={form.avatarUrl.trim() && !form.errors.avatarUrl ? form.avatarUrl.trim() : me.avatarUrl}
            name={form.name}
            className="size-16 text-xl"
          />
          <div className="min-w-0">
            <div className="truncate font-medium">{form.name || 'Your name'}</div>
            <div className="text-muted-foreground truncate text-sm">{form.email || 'No email'}</div>
          </div>
        </div>

        <ProfileFields form={form} editAvatar />

        {form.failed && (
          <p role="alert" className="text-destructive mt-5 text-sm">
            We couldn't save your changes. Please try again.
          </p>
        )}

        <div className="mt-6 flex items-center gap-3">
          <Button type="submit" disabled={form.saving || !form.dirty}>
            {form.saving && <Loader2 className="animate-spin" />}
            {form.saving ? 'Saving…' : 'Save changes'}
          </Button>
          {form.saved && (
            <span role="status" className="text-ok rise flex items-center gap-1 text-sm">
              <Check className="size-4" /> Saved
            </span>
          )}
        </div>
      </form>
    </Section>
  )
}

function AppearanceSection() {
  const { theme, setTheme } = useTheme()
  const options = [
    { value: 'light', label: 'Light', icon: Sun },
    { value: 'dark', label: 'Dark', icon: Moon },
  ] as const
  return (
    <Section title="Appearance" description="Choose how Nimbus looks on this device.">
      <div className="grid max-w-sm grid-cols-2 gap-3" role="radiogroup" aria-label="Theme">
        {options.map(({ value, label, icon: Icon }) => (
          <button
            key={value}
            type="button"
            role="radio"
            aria-checked={theme === value}
            onClick={() => setTheme(value)}
            className={cn(
              'flex items-center gap-2 rounded-xl border p-3 text-sm font-medium transition-colors',
              theme === value ? 'border-brand-to bg-brand-to/10' : 'border-border hover:bg-muted/60',
            )}
          >
            <Icon className="size-4" /> {label}
          </button>
        ))}
      </div>
    </Section>
  )
}
