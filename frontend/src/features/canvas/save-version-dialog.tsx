import { useState } from 'react'
import { Loader2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useSaveVersion } from '@/features/apps/queries'
import type { SaveVersionResult } from '@/features/apps/types'

interface Props {
  appId: string
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Makes sure the server draft is current before it is snapshotted. */
  flush: () => Promise<boolean>
  onSaved: (result: SaveVersionResult) => void
}

export function SaveVersionDialog({ appId, open, onOpenChange, flush, onSaved }: Props) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        {open && <Form appId={appId} flush={flush} onSaved={onSaved} onClose={() => onOpenChange(false)} />}
      </DialogContent>
    </Dialog>
  )
}

function Form({ appId, flush, onSaved, onClose }: Omit<Props, 'open' | 'onOpenChange'> & { onClose: () => void }) {
  const save = useSaveVersion(appId)
  const [note, setNote] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    setBusy(true)
    const ok = await flush()
    if (!ok) {
      setBusy(false)
      return setError("Your latest edits couldn't be saved yet, so a version wasn't created.")
    }
    save.mutate(note.trim(), {
      onSuccess: (result) => {
        onSaved(result)
        onClose()
      },
      onError: (e) => setError(e.message),
      onSettled: () => setBusy(false),
    })
  }

  return (
    <form onSubmit={submit} className="grid gap-4">
      <DialogHeader>
        <DialogTitle>Save version</DialogTitle>
        <DialogDescription>Keep a snapshot of the current design that you can restore later.</DialogDescription>
      </DialogHeader>
      <div className="grid gap-1.5">
        <Label htmlFor="version-note">Note (optional)</Label>
        <Input id="version-note" autoFocus maxLength={200} autoComplete="off" placeholder="Added the cache" value={note} onChange={(e) => setNote(e.target.value)} />
        {error && <p role="alert" className="text-destructive text-xs">{error}</p>}
      </div>
      <DialogFooter>
        <Button type="button" variant="outline" onClick={onClose}>Cancel</Button>
        <Button type="submit" disabled={busy}>
          {busy && <Loader2 className="animate-spin" />}
          Save version
        </Button>
      </DialogFooter>
    </form>
  )
}
