import { useEffect, useState } from 'react'
import { ArrowLeft, Check, Loader2 } from 'lucide-react'
import { Link, useLocation, useParams } from 'react-router'
import { Button, buttonVariants } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Skeleton } from '@/components/ui/skeleton'
import { relativeTime, settingText, StatusBadge, SyncBadge } from '@/features/workers/parts'
import { useReplaceWorkerSettings, useWorker } from '@/features/workers/queries'
import { LIMITS, type SettingValues, type Worker } from '@/features/workers/types'
import { ApiError } from '@/lib/api'

export default function AdminWorkerDetailPage() {
  const { name = '' } = useParams()
  const worker = useWorker(name)

  return (
    <div className="mx-auto max-w-5xl">
      <Link to="/admin/workers" className="text-muted-foreground hover:text-foreground mb-4 inline-flex items-center gap-1.5 text-sm">
        <ArrowLeft className="size-4" /> Workers
      </Link>

      {worker.isPending ? (
        <div className="space-y-4" aria-busy="true" aria-label="Loading worker">
          <Skeleton className="h-9 w-64" />
          <Skeleton className="h-64 rounded-2xl" />
        </div>
      ) : worker.isError ? (
        <NotFound error={worker.error} />
      ) : (
        <Details worker={worker.data} />
      )}
    </div>
  )
}

function NotFound({ error }: { error: Error }) {
  const missing = error instanceof ApiError && error.status === 404
  return (
    <div role="alert" className="border-border rounded-2xl border px-6 py-16 text-center">
      <h1 className="text-xl font-semibold">{missing ? 'Worker not found' : "Couldn't load this worker"}</h1>
      <p className="text-muted-foreground mt-2 text-sm">{missing ? 'No worker is registered under that name.' : error.message}</p>
      <Link to="/admin/workers" className={buttonVariants({ variant: 'outline' }) + ' mt-6'}>
        Back to workers
      </Link>
    </div>
  )
}

function Details({ worker: w }: { worker: Worker }) {
  const s = w.settings
  return (
    <>
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="font-mono text-2xl font-semibold tracking-tight">{w.name}</h1>
        <StatusBadge status={w.status} />
      </div>
      <p className="text-muted-foreground mt-1 text-sm">
        Pool <span className="text-foreground font-mono">{w.pool}</span>
        {w.ordinal != null ? ` · slot ${w.ordinal}` : ' · asked for this name'}
      </p>

      <div className="mt-8 grid gap-6 lg:grid-cols-2">
        <section className="card-surface rounded-2xl p-6">
          <h2 className="font-medium">Details</h2>
          <dl className="mt-4 space-y-3 text-sm">
            <Row label="Running jobs" value={`${w.heldJobs}${s.effective.parallelJobs != null ? ` of ${s.effective.parallelJobs}` : ''}`} />
            <Row label="Last heartbeat" value={relativeTime(w.lastSeenAt)} title={new Date(w.lastSeenAt).toLocaleString()} />
            <Row label="Slot lease" value={w.status === 'ONLINE' ? `expires ${relativeTime(w.leaseExpiresAt)}` : `expired ${relativeTime(w.leaseExpiresAt)}`} />
            <Row label="Started" value={relativeTime(w.startedAt)} title={new Date(w.startedAt).toLocaleString()} />
            <Row label="Version" value={w.version ?? '—'} mono />
            <Row label="Host" value={w.host ?? '—'} mono />
            <Row label="Instance" value={w.instanceId} mono />
          </dl>
          <p className="text-muted-foreground mt-4 text-xs text-pretty">
            The name stays with the slot across restarts. The instance changes every time the worker starts.
          </p>
        </section>

        <section className="card-surface rounded-2xl p-6">
          <div className="flex items-start justify-between gap-3">
            <h2 className="font-medium">Settings</h2>
            <SyncBadge settings={s} offline={w.status === 'OFFLINE'} />
          </div>
          {s.problem && (
            <p role="alert" className="text-destructive mt-3 text-xs text-pretty">
              The worker refused the stored settings: {s.problem}
            </p>
          )}

          <table className="mt-4 w-full text-sm">
            <thead>
              <tr className="text-muted-foreground text-left text-xs">
                <th className="pb-2 font-medium">Setting</th>
                <th className="pb-2 font-medium">This worker</th>
                <th className="pb-2 font-medium">Pool default</th>
                <th className="pb-2 font-medium">Applies</th>
              </tr>
            </thead>
            <tbody className="divide-border divide-y">
              <SettingRow label="Parallel jobs" field="parallelJobs" unit="" s={s} />
              <SettingRow label="Lease length" field="leaseSeconds" unit=" s" s={s} />
            </tbody>
          </table>
          <p className="text-muted-foreground mt-3 text-xs text-pretty">
            “Default” means nothing is stored, so the worker uses the value it started with. Versions: stored {s.desiredVersion}, applied {s.appliedVersion ?? '—'}.
          </p>
        </section>
      </div>

      <SettingsForm worker={w} />
    </>
  )
}

function Row({ label, value, mono, title }: { label: string; value: string; mono?: boolean; title?: string }) {
  return (
    <div className="grid grid-cols-[8rem_1fr] gap-3">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className={mono ? 'font-mono text-xs break-all' : undefined} title={title}>
        {value}
      </dd>
    </div>
  )
}

function SettingRow({ label, field, unit, s }: { label: string; field: keyof SettingValues; unit: string; s: Worker['settings'] }) {
  const own = s.override[field]
  return (
    <tr>
      <td className="py-2.5 pr-3">{label}</td>
      <td className="py-2.5 pr-3 tabular-nums">{settingText(own, unit, '—')}</td>
      <td className="text-muted-foreground py-2.5 pr-3 tabular-nums">{settingText(s.poolDefault[field], unit, '—')}</td>
      <td className="py-2.5 font-medium tabular-nums">
        {settingText(s.effective[field], unit, 'default')}
        {own == null && s.effective[field] != null && <span className="text-muted-foreground font-normal"> (pool)</span>}
      </td>
    </tr>
  )
}

// ---- editing this worker's own settings ----

function parse(text: string, min: number, max: number, what: string): { value: number | null; error?: string } {
  const t = text.trim()
  if (t === '') return { value: null }
  if (!/^\d+$/.test(t)) return { value: null, error: `${what} must be a whole number` }
  const n = Number(t)
  if (n < min || n > max) return { value: null, error: `${what} must be between ${min} and ${max}` }
  return { value: n }
}

function SettingsForm({ worker: w }: { worker: Worker }) {
  const o = w.settings.override
  // Starts again from the server's values whenever they change (after a save), but not on every poll.
  return <FormBody key={`${o.parallelJobs}|${o.leaseSeconds}|${w.settings.overrideUpdatedAt}`} worker={w} />
}

function FormBody({ worker: w }: { worker: Worker }) {
  // The page renders after the data arrives, so the browser can't scroll to #settings by itself.
  const { hash } = useLocation()
  useEffect(() => {
    if (hash === '#settings') document.getElementById('settings')?.scrollIntoView({ block: 'start' })
  }, [hash])
  const save = useReplaceWorkerSettings(w.name)
  const own = w.settings.override
  const [jobs, setJobs] = useState(own.parallelJobs?.toString() ?? '')
  const [lease, setLease] = useState(own.leaseSeconds?.toString() ?? '')
  const [message, setMessage] = useState<string | null>(null)

  const pj = parse(jobs, LIMITS.parallelJobs.min, LIMITS.parallelJobs.max, 'Parallel jobs')
  const ls = parse(lease, LIMITS.leaseSeconds.min, LIMITS.leaseSeconds.max, 'Lease length')
  const unchanged = pj.value === own.parallelJobs && ls.value === own.leaseSeconds && !pj.error && !ls.error

  function submit(e: React.FormEvent, values?: { parallelJobs: number | null; leaseSeconds: number | null }) {
    e.preventDefault()
    const body = values ?? { parallelJobs: pj.value, leaseSeconds: ls.value }
    if (!values && (pj.error || ls.error)) return
    setMessage(null)
    save.mutate(body, {
      onSuccess: () => setMessage('Saved. The worker applies it on its next heartbeat, within about 10 seconds.'),
    })
  }

  const apiProblems = save.error instanceof ApiError ? ((save.error.body?.errors as { message: string }[] | undefined) ?? []) : []

  return (
    <form id="settings" onSubmit={submit} noValidate className="border-border bg-card mt-6 scroll-mt-6 rounded-2xl border p-6">
      <h2 className="font-medium">Change this worker's settings</h2>
      <p className="text-muted-foreground mt-1 text-sm text-pretty">
        Leave a field empty to inherit it from the pool. These apply to <span className="font-mono">{w.name}</span> only, and stay with the name when the worker restarts.
      </p>

      <div className="mt-5 grid gap-5 sm:grid-cols-2">
        <div className="grid gap-1.5">
          <Label htmlFor="parallel-jobs">Parallel jobs</Label>
          <Input
            id="parallel-jobs"
            inputMode="numeric"
            className="h-10"
            placeholder={settingText(w.settings.poolDefault.parallelJobs, '', 'worker default')}
            value={jobs}
            onChange={(e) => setJobs(e.target.value)}
            aria-invalid={!!pj.error}
          />
          <p className={pj.error ? 'text-destructive text-xs' : 'text-muted-foreground text-xs'}>
            {pj.error ?? `${LIMITS.parallelJobs.min}–${LIMITS.parallelJobs.max}. Lowering it doesn't stop jobs that are already running.`}
          </p>
        </div>
        <div className="grid gap-1.5">
          <Label htmlFor="lease-seconds">Lease length (seconds)</Label>
          <Input
            id="lease-seconds"
            inputMode="numeric"
            className="h-10"
            placeholder={settingText(w.settings.poolDefault.leaseSeconds, '', 'worker default')}
            value={lease}
            onChange={(e) => setLease(e.target.value)}
            aria-invalid={!!ls.error}
          />
          <p className={ls.error ? 'text-destructive text-xs' : 'text-muted-foreground text-xs'}>
            {ls.error ?? `${LIMITS.leaseSeconds.min}–${LIMITS.leaseSeconds.max}. Applies to jobs claimed after the change.`}
          </p>
        </div>
      </div>

      {save.isError && (
        <div role="alert" className="text-destructive mt-4 text-sm">
          <p>{save.error.message}</p>
          {apiProblems.length > 0 && (
            <ul className="mt-1 list-disc pl-5 text-xs">
              {apiProblems.map((p, i) => (
                <li key={i}>{p.message}</li>
              ))}
            </ul>
          )}
        </div>
      )}
      {message && !save.isError && (
        <p role="status" className="text-ok mt-4 flex items-center gap-1.5 text-sm">
          <Check className="size-4" /> {message}
        </p>
      )}

      <div className="mt-5 flex flex-wrap items-center gap-3">
        <Button type="submit" disabled={save.isPending || unchanged || !!pj.error || !!ls.error}>
          {save.isPending && <Loader2 className="animate-spin" />}
          Save changes
        </Button>
        <Button
          type="button"
          variant="ghost"
          disabled={save.isPending || (own.parallelJobs == null && own.leaseSeconds == null)}
          onClick={(e) => submit(e, { parallelJobs: null, leaseSeconds: null })}
        >
          Reset to the pool's values
        </Button>
      </div>
    </form>
  )
}
