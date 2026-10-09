import { useState } from 'react'
import { Check, Loader2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useReplacePoolDefaults } from './queries'
import { SettingsFields } from './settings-fields'
import { saveProblems, useSettingsFields } from './use-settings-fields'
import type { Pool } from './types'

/** Edit what the workers of a pool use when they have no value of their own. */
export function PoolDefaultsDialog({ pool, onClose }: { pool: Pool | null; onClose: () => void }) {
  return (
    <Dialog open={pool !== null} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="sm:max-w-lg">
        {/* Closing unmounts the form, so reopening starts from what is stored now; saving doesn't restart it. */}
        {pool && <Form key={pool.name} pool={pool} onClose={onClose} />}
      </DialogContent>
    </Dialog>
  )
}

function Form({ pool, onClose }: { pool: Pool; onClose: () => void }) {
  const save = useReplacePoolDefaults(pool.name)
  const fields = useSettingsFields(pool.defaults)
  const [saved, setSaved] = useState(false)
  const problems = saveProblems(save.error)
  const hasDefaults = pool.defaults.parallelJobs != null || pool.defaults.leaseSeconds != null

  function submit(e: React.FormEvent, values = fields.values) {
    e.preventDefault()
    if (!fields.valid) return
    setSaved(false)
    save.mutate(values, {
      onSuccess: () => {
        setSaved(true)
        // Clearing leaves nothing to show: empty the inputs too, so they match what is stored.
        if (values.parallelJobs == null && values.leaseSeconds == null) {
          fields.setJobs('')
          fields.setLease('')
        }
      },
    })
  }

  return (
    <form onSubmit={submit} noValidate className="grid gap-5">
      <DialogHeader>
        <DialogTitle>
          Defaults for pool <span className="font-mono">{pool.name}</span>
        </DialogTitle>
        <DialogDescription className="text-pretty">
          Used by every worker in this pool that has no value of its own. Leave a field empty to let each worker use the value it started with.
          {pool.overriding > 0 && (
            <>
              {' '}
              {pool.overriding === 1
                ? `1 of ${pool.workers} workers has a value of its own and keeps it.`
                : `${pool.overriding} of ${pool.workers} workers have values of their own and keep them.`}
            </>
          )}
        </DialogDescription>
      </DialogHeader>

      <SettingsFields fields={fields} idPrefix="pool" jobsPlaceholder="worker default" leasePlaceholder="worker default" />

      {save.isError && (
        <div role="alert" className="text-destructive text-sm">
          <p>{save.error.message}</p>
          {problems.length > 0 && (
            <ul className="mt-1 list-disc pl-5 text-xs">
              {problems.map((p, i) => (
                <li key={i}>{p}</li>
              ))}
            </ul>
          )}
        </div>
      )}
      {saved && !save.isError && (
        <p role="status" className="text-ok flex items-center gap-1.5 text-sm">
          <Check className="size-4" /> Saved. Workers apply it on their next heartbeat, within about 10 seconds.
        </p>
      )}

      <div className="flex flex-wrap items-center gap-3">
        <Button type="submit" disabled={save.isPending || !fields.valid || fields.unchangedFrom(pool.defaults)}>
          {save.isPending && <Loader2 className="animate-spin" />}
          Save defaults
        </Button>
        <Button type="button" variant="ghost" disabled={save.isPending || !hasDefaults} onClick={(e) => submit(e, { parallelJobs: null, leaseSeconds: null })}>
          Clear defaults
        </Button>
        <Button type="button" variant="ghost" className="ml-auto" onClick={onClose}>
          Close
        </Button>
      </div>
    </form>
  )
}
