import { useState } from 'react'
import { Pencil } from 'lucide-react'
import { Input } from '@/components/ui/input'
import { useRenameApp } from '@/features/apps/queries'

export function RenameTitle({ appId, name, onRenamed }: { appId: string; name: string; onRenamed: (name: string) => void }) {
  const rename = useRenameApp(appId)
  const [editing, setEditing] = useState(false)
  const [value, setValue] = useState(name)
  const [error, setError] = useState<string | null>(null)

  function commit() {
    const next = value.trim()
    if (next === name) return setEditing(false)
    if (!next) return setError('Give your app a name')
    rename.mutate(next, {
      onSuccess: (app) => {
        onRenamed(app.name)
        setEditing(false)
        setError(null)
      },
      onError: (e) => setError(e.message),
    })
  }

  if (!editing) {
    return (
      <button
        type="button"
        onClick={() => { setValue(name); setError(null); setEditing(true) }}
        className="hover:bg-muted group flex max-w-[16rem] items-center gap-1.5 rounded-md px-2 py-1 text-sm font-medium"
        title="Rename"
      >
        <span className="truncate">{name}</span>
        <Pencil className="text-muted-foreground size-3 opacity-0 transition-opacity group-hover:opacity-100" />
      </button>
    )
  }

  return (
    <div className="relative">
      <Input
        autoFocus
        aria-label="App name"
        value={value}
        maxLength={60}
        className="h-8 w-48"
        aria-invalid={!!error}
        onChange={(e) => setValue(e.target.value)}
        onBlur={commit}
        onKeyDown={(e) => {
          if (e.key === 'Enter') commit()
          if (e.key === 'Escape') { setError(null); setEditing(false) }
        }}
      />
      {error && <p role="alert" className="text-destructive bg-background absolute top-full left-0 z-20 mt-1 rounded border px-2 py-1 text-xs whitespace-nowrap">{error}</p>}
    </div>
  )
}
