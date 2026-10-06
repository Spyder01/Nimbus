import { useState } from 'react'
import { Loader2, Trash2 } from 'lucide-react'
import {
  AlertDialog,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogMedia,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useDeleteApp } from './queries'
import type { AppSummary } from './types'

// `app` null = closed. Deleting needs the app's name typed back, since it can't be undone.
export function DeleteAppDialog({ app, onClose }: { app: AppSummary | null; onClose: () => void }) {
  return (
    <AlertDialog open={!!app} onOpenChange={(open) => !open && onClose()}>
      <AlertDialogContent>{app && <DeleteForm key={app.id} app={app} onClose={onClose} />}</AlertDialogContent>
    </AlertDialog>
  )
}

function DeleteForm({ app, onClose }: { app: AppSummary; onClose: () => void }) {
  const del = useDeleteApp()
  const [typed, setTyped] = useState('')
  const matches = typed.trim().toLowerCase() === app.name.toLowerCase()

  function submit(e: React.FormEvent) {
    e.preventDefault()
    if (matches) del.mutate(app.id, { onSuccess: onClose })
  }

  return (
    <form onSubmit={submit} className="grid gap-4">
      <AlertDialogHeader>
        <AlertDialogMedia className="bg-destructive/10 text-destructive">
          <Trash2 />
        </AlertDialogMedia>
        <AlertDialogTitle>Delete “{app.name}”?</AlertDialogTitle>
        <AlertDialogDescription>
          This permanently deletes the app, its design and all saved versions. This can't be undone.
        </AlertDialogDescription>
      </AlertDialogHeader>
      <div className="grid gap-1.5">
        <Label htmlFor="confirm-name">
          Type <span className="font-mono font-semibold">{app.name}</span> to confirm
        </Label>
        <Input id="confirm-name" autoFocus autoComplete="off" value={typed} onChange={(e) => setTyped(e.target.value)} />
        {del.isError && (
          <p role="alert" className="text-destructive text-xs">
            {del.error.message}
          </p>
        )}
      </div>
      <AlertDialogFooter>
        <Button type="button" variant="outline" onClick={onClose}>
          Cancel
        </Button>
        <Button type="submit" variant="destructive" disabled={!matches || del.isPending}>
          {del.isPending && <Loader2 className="animate-spin" />}
          Delete app
        </Button>
      </AlertDialogFooter>
    </form>
  )
}
