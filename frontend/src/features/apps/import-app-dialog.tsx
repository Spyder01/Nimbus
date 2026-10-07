import { useState } from 'react'
import { Loader2 } from 'lucide-react'
import { useNavigate } from 'react-router'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { ApiError } from '@/lib/api'
import { useImportApp } from './queries'

const MAX_BYTES = 256 * 1024

export function ImportAppDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        {/* Remounts on open so the form always starts clean. */}
        {open && <ImportForm onClose={() => onOpenChange(false)} />}
      </DialogContent>
    </Dialog>
  )
}

interface Problem {
  line: number | null
  message: string
}

function ImportForm({ onClose }: { onClose: () => void }) {
  const navigate = useNavigate()
  const importApp = useImportApp()
  const [name, setName] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [problems, setProblems] = useState<Problem[]>([])
  const [reading, setReading] = useState(false)

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setProblems([])
    const trimmed = name.trim()
    if (!trimmed) return setError('Give the new app a name')
    if (trimmed.length > 60) return setError('Keep the name under 60 characters')
    if (!file) return setError('Choose a YAML file to import')
    if (file.size > MAX_BYTES) return setError(`That file is too large (the limit is ${MAX_BYTES / 1024} KB)`)
    setError(null)

    setReading(true)
    let yaml: string
    try {
      yaml = await file.text()
    } catch {
      setReading(false)
      return setError("Couldn't read that file")
    }
    setReading(false)

    importApp.mutate(
      { name: trimmed, yaml },
      {
        onSuccess: (app) => {
          onClose()
          navigate(`/apps/${app.id}`)
        },
        onError: (e) => {
          setError(e.message)
          setProblems(e instanceof ApiError ? ((e.body?.errors as Problem[] | undefined) ?? []).filter((p) => p && p.message) : [])
        },
      },
    )
  }

  const busy = reading || importApp.isPending
  return (
    <form onSubmit={submit} noValidate className="grid gap-4">
      <DialogHeader>
        <DialogTitle>Import app from YAML</DialogTitle>
        <DialogDescription>Create a new app from a YAML file, such as one exported from the canvas.</DialogDescription>
      </DialogHeader>

      <div className="grid gap-1.5">
        <Label htmlFor="import-name">App name</Label>
        <Input id="import-name" autoFocus autoComplete="off" placeholder="my-store" value={name} onChange={(e) => setName(e.target.value)} />
      </div>

      <div className="grid gap-1.5">
        <Label htmlFor="import-file">YAML file</Label>
        <Input
          id="import-file"
          type="file"
          accept=".yaml,.yml,text/yaml,application/x-yaml"
          className="h-auto py-1.5"
          onChange={(e) => setFile(e.target.files?.[0] ?? null)}
        />
      </div>

      {error && (
        <div role="alert" className="text-destructive text-sm">
          <p>{error}</p>
          {problems.length > 0 && (
            <ul className="mt-1.5 max-h-40 list-disc space-y-0.5 overflow-y-auto pl-5 text-xs">
              {problems.map((p, i) => (
                <li key={i}>
                  {p.line ? <span className="font-mono">Line {p.line}: </span> : null}
                  {p.message}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}

      <DialogFooter>
        <Button type="button" variant="outline" onClick={onClose}>
          Cancel
        </Button>
        <Button type="submit" disabled={busy}>
          {busy && <Loader2 className="animate-spin" />}
          Import app
        </Button>
      </DialogFooter>
    </form>
  )
}
