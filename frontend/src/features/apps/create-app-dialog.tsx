import { useState } from 'react'
import { Loader2 } from 'lucide-react'
import { useNavigate } from 'react-router'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useCreateApp } from './queries'

export function CreateAppDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        {/* Remounts on open so the form always starts clean. */}
        {open && <CreateForm onClose={() => onOpenChange(false)} />}
      </DialogContent>
    </Dialog>
  )
}

function CreateForm({ onClose }: { onClose: () => void }) {
  const navigate = useNavigate()
  const create = useCreateApp()
  const [name, setName] = useState('')
  const [error, setError] = useState<string | null>(null)

  function submit(e: React.FormEvent) {
    e.preventDefault()
    const trimmed = name.trim()
    if (!trimmed) return setError('Give your app a name')
    if (trimmed.length > 60) return setError('Keep the name under 60 characters')
    setError(null)
    create.mutate(trimmed, {
      onSuccess: (app) => {
        onClose()
        navigate(`/apps/${app.id}`)
      },
      onError: (e) => setError(e.message),
    })
  }

  return (
    <form onSubmit={submit} noValidate className="grid gap-4">
      <DialogHeader>
        <DialogTitle>Create app</DialogTitle>
        <DialogDescription>Name it now, then design its containers on the canvas.</DialogDescription>
      </DialogHeader>
      <div className="grid gap-1.5">
        <Label htmlFor="app-name">Name</Label>
        <Input
          id="app-name"
          autoFocus
          autoComplete="off"
          placeholder="my-store"
          value={name}
          onChange={(e) => setName(e.target.value)}
          aria-invalid={!!error}
          aria-describedby={error ? 'app-name-error' : undefined}
        />
        {error && (
          <p id="app-name-error" role="alert" className="text-destructive text-xs">
            {error}
          </p>
        )}
      </div>
      <DialogFooter>
        <Button type="button" variant="outline" onClick={onClose}>
          Cancel
        </Button>
        <Button type="submit" disabled={create.isPending}>
          {create.isPending && <Loader2 className="animate-spin" />}
          Create app
        </Button>
      </DialogFooter>
    </form>
  )
}
