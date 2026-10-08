import { Pencil, Server } from 'lucide-react'
import { Link } from 'react-router'
import { Button, buttonVariants } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { useWorkers } from '@/features/workers/queries'
import { effectiveJobs, relativeTime, StatusBadge, SyncBadge } from '@/features/workers/parts'
import type { Worker } from '@/features/workers/types'
import { ApiError } from '@/lib/api'

export default function AdminWorkersPage() {
  const workers = useWorkers()

  return (
    <div className="mx-auto max-w-6xl">
      <h1 className="text-2xl font-semibold tracking-tight">Workers</h1>
      <p className="text-muted-foreground mt-1 text-sm">
        Every worker that has registered. An online worker sends a heartbeat about every 10 seconds; one that goes quiet is shown as offline.
      </p>

      <div className="mt-8">
        {workers.isPending ? (
          <Loading />
        ) : workers.isError ? (
          <ErrorState error={workers.error} onRetry={() => workers.refetch()} />
        ) : workers.data.length === 0 ? (
          <EmptyState />
        ) : (
          <Pools workers={workers.data} />
        )}
      </div>
    </div>
  )
}

function Pools({ workers }: { workers: Worker[] }) {
  const online = workers.filter((w) => w.status === 'ONLINE').length
  const jobs = workers.reduce((sum, w) => sum + w.heldJobs, 0)
  const pools = [...new Set(workers.map((w) => w.pool))]

  return (
    <div className="space-y-8">
      <dl className="grid grid-cols-3 gap-3 sm:max-w-md">
        <Stat label="Online" value={`${online} / ${workers.length}`} />
        <Stat label="Running jobs" value={String(jobs)} />
        <Stat label="Pools" value={String(pools.length)} />
      </dl>

      {pools.map((pool) => (
        <section key={pool} aria-labelledby={`pool-${pool}`}>
          <h2 id={`pool-${pool}`} className="text-muted-foreground mb-3 text-xs font-medium tracking-wide uppercase">
            Pool <span className="text-foreground font-mono normal-case">{pool}</span>
          </h2>
          <div className="card-surface overflow-x-auto rounded-2xl">
            <table className="w-full min-w-[50rem] text-sm">
              <thead>
                <tr className="text-muted-foreground border-border bg-muted/40 border-b text-left text-xs">
                  <th className="px-4 py-3 font-medium">Worker</th>
                  <th className="px-4 py-3 font-medium">Status</th>
                  <th className="px-4 py-3 font-medium">Jobs</th>
                  <th className="px-4 py-3 font-medium">Last heartbeat</th>
                  <th className="px-4 py-3 font-medium">Version</th>
                  <th className="px-4 py-3 font-medium">Settings</th>
                  <th className="px-4 py-3" />
                </tr>
              </thead>
              <tbody className="divide-border divide-y">
                {workers
                  .filter((w) => w.pool === pool)
                  .map((w) => (
                    <tr key={w.name} className="hover:bg-muted/40 transition-colors">
                      <td className="px-4 py-3">
                        <Link to={`/admin/workers/${encodeURIComponent(w.name)}`} className="font-mono font-medium hover:underline">
                          {w.name}
                        </Link>
                        {w.host && <div className="text-muted-foreground max-w-[14rem] truncate text-xs">{w.host}</div>}
                      </td>
                      <td className="px-4 py-3">
                        <StatusBadge status={w.status} />
                      </td>
                      <td className="px-4 py-3 tabular-nums">
                        {w.heldJobs}
                        <span className="text-muted-foreground"> / {effectiveJobs(w)}</span>
                      </td>
                      <td className="text-muted-foreground px-4 py-3 whitespace-nowrap">{relativeTime(w.lastSeenAt)}</td>
                      <td className="text-muted-foreground px-4 py-3 font-mono text-xs">{w.version ?? '—'}</td>
                      <td className="px-4 py-3">
                        <SyncBadge settings={w.settings} offline={w.status === 'OFFLINE'} />
                      </td>
                      <td className="px-4 py-3 text-right">
                        <Link
                          to={`/admin/workers/${encodeURIComponent(w.name)}#settings`}
                          className={buttonVariants({ variant: 'outline', size: 'sm' })}
                          aria-label={`Edit settings of ${w.name}`}
                        >
                          <Pencil /> Edit
                        </Link>
                      </td>
                    </tr>
                  ))}
              </tbody>
            </table>
          </div>
        </section>
      ))}
    </div>
  )
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="card-surface rounded-xl px-4 py-3">
      <dt className="text-muted-foreground text-xs">{label}</dt>
      <dd className="mt-0.5 text-lg font-semibold tabular-nums">{value}</dd>
    </div>
  )
}

function Loading() {
  return (
    <div className="space-y-3" aria-busy="true" aria-label="Loading workers">
      <Skeleton className="h-16 w-full max-w-md rounded-xl" />
      <Skeleton className="h-48 rounded-2xl" />
    </div>
  )
}

function EmptyState() {
  return (
    <div className="border-border flex flex-col items-center rounded-2xl border border-dashed px-6 py-20 text-center">
      <span className="bg-brand-gradient mb-5 grid size-12 place-items-center rounded-xl text-white">
        <Server className="size-6" />
      </span>
      <h2 className="text-lg font-medium">No workers have registered yet</h2>
      <p className="text-muted-foreground mt-1.5 max-w-sm text-sm text-pretty">
        Start a worker and it will appear here within a few seconds.
      </p>
    </div>
  )
}

function ErrorState({ error, onRetry }: { error: Error; onRetry: () => void }) {
  const forbidden = error instanceof ApiError && error.status === 403
  return (
    <div role="alert" className="border-destructive/30 bg-destructive/5 rounded-2xl border px-6 py-10 text-center">
      <p className="font-medium">{forbidden ? 'Admins only' : "Couldn't load the workers"}</p>
      <p className="text-muted-foreground mt-1 text-sm">{forbidden ? "Your account doesn't have the admin role." : error.message}</p>
      {!forbidden && (
        <Button variant="outline" className="mt-5" onClick={onRetry}>
          Try again
        </Button>
      )}
    </div>
  )
}
