import { useState } from 'react'
import { Input } from '@/components/ui/input'
import { api } from '@/lib/api'
import type { Me } from '@/lib/auth'

const EMAIL = /^[^@\s]+@[^@\s]+\.[^@\s]+$/

interface Errors {
  name?: string
  email?: string
  avatarUrl?: string
}

// Shared by onboarding (/profile) and Settings: state, validation and the PUT /api/me call.
export function useProfileForm(me: Me, onSaved: (me: Me) => void, { editAvatar = false } = {}) {
  const [name, setName] = useState(me.name ?? '')
  const [email, setEmail] = useState(me.email ?? '')
  const [avatarUrl, setAvatarUrl] = useState(me.avatarUrl ?? '')
  const [errors, setErrors] = useState<Errors>({})
  const [saving, setSaving] = useState(false)
  const [failed, setFailed] = useState(false)
  const [saved, setSaved] = useState(false)

  const dirty =
    name !== (me.name ?? '') || email !== (me.email ?? '') || (editAvatar && avatarUrl !== (me.avatarUrl ?? ''))

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    const found: Errors = {}
    if (!name.trim()) found.name = 'Enter your name'
    else if (name.trim().length > 100) found.name = 'Keep it under 100 characters'
    if (email.trim() && !EMAIL.test(email.trim())) found.email = 'Enter a valid email address'
    if (editAvatar && avatarUrl.trim() && !/^https:\/\/\S+$/.test(avatarUrl.trim()))
      found.avatarUrl = 'Use a full https:// image link'
    setErrors(found)
    if (Object.keys(found).length) return

    setSaving(true)
    setFailed(false)
    setSaved(false)
    try {
      // The API replaces avatarUrl, so when it isn't editable send the current one back.
      const avatar = editAvatar ? avatarUrl.trim() || null : me.avatarUrl
      const r = await api('/api/me', {
        method: 'PUT',
        json: { name: name.trim(), email: email.trim() || null, avatarUrl: avatar },
      })
      if (!r.ok) throw new Error(String(r.status))
      onSaved((await r.json()) as Me)
      setSaved(true)
      window.setTimeout(() => setSaved(false), 2500)
    } catch {
      setFailed(true)
    } finally {
      setSaving(false)
    }
  }

  return { name, setName, email, setEmail, avatarUrl, setAvatarUrl, errors, saving, failed, saved, dirty, submit }
}

export type ProfileFormState = ReturnType<typeof useProfileForm>

export function Field({
  label,
  hint,
  error,
  htmlFor,
  children,
}: {
  label: string
  hint?: string
  error?: string
  htmlFor: string
  children: React.ReactNode
}) {
  return (
    <div className="space-y-1.5">
      <label htmlFor={htmlFor} className="text-sm font-medium">
        {label}
      </label>
      {children}
      {error ? (
        <p id={`${htmlFor}-error`} className="text-destructive text-xs">
          {error}
        </p>
      ) : (
        hint && <p className="text-muted-foreground text-xs">{hint}</p>
      )}
    </div>
  )
}

export function ProfileFields({ form, editAvatar = false }: { form: ProfileFormState; editAvatar?: boolean }) {
  const { errors } = form
  return (
    <div className="space-y-5">
      <Field label="Full name" error={errors.name} htmlFor="name">
        <Input
          id="name"
          autoComplete="name"
          className="h-10"
          value={form.name}
          onChange={(e) => form.setName(e.target.value)}
          aria-invalid={!!errors.name}
          aria-describedby={errors.name ? 'name-error' : undefined}
        />
      </Field>
      <Field
        label="Email"
        hint="Optional. We'll only use it for deploy and billing notices."
        error={errors.email}
        htmlFor="email"
      >
        <Input
          id="email"
          type="email"
          autoComplete="email"
          placeholder="you@company.com"
          className="h-10"
          value={form.email}
          onChange={(e) => form.setEmail(e.target.value)}
          aria-invalid={!!errors.email}
          aria-describedby={errors.email ? 'email-error' : undefined}
        />
      </Field>
      {editAvatar && (
        <Field
          label="Avatar link"
          hint="Link to an image. Leave empty to show your initial."
          error={errors.avatarUrl}
          htmlFor="avatarUrl"
        >
          <Input
            id="avatarUrl"
            inputMode="url"
            placeholder="https://…"
            className="h-10"
            value={form.avatarUrl}
            onChange={(e) => form.setAvatarUrl(e.target.value)}
            aria-invalid={!!errors.avatarUrl}
            aria-describedby={errors.avatarUrl ? 'avatarUrl-error' : undefined}
          />
        </Field>
      )}
    </div>
  )
}
