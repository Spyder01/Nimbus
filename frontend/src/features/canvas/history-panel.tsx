import { useState } from 'react'
import { History, Loader2, RotateCcw, X } from 'lucide-react'
import {
  AlertDialog,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { useRestoreVersion, useVersions } from '@/features/apps/queries'
import { DeploymentBadge } from '@/features/apps/state-badge'
import type { Deployment, Draft, VersionSummary } from '@/features/apps/types'
import { cn } from '@/lib/utils'
import { timeAgo } from '@/lib/time'

interface Props {
  appId: string
  /** Saves pending edits and returns the draft revision the restore should be based on (null = couldn't save). */
  prepareRestore: () => Promise<number | null>
  onRestored: (draft: Draft) => void
  onClose: () => void
  deployments: Deployment[]
}

export function HistoryPanel({ appId, prepareRestore, onRestored, onClose, deployments: deploymentList }: Props) {
  const [tab, setTab] = useState<'versions' | 'deployments'>('versions')
  const versions = useVersions(appId, true)
  const restore = useRestoreVersion(appId)
  const [target, setTarget] = useState<VersionSummary | null>(null)
  const [error, setError] = useState<string | null>(null)

  async function confirmRestore() {
    if (!target) return
    setError(null)
    const base = await prepareRestore()
    if (base == null) return setError("Your latest edits couldn't be saved, so nothing was restored.")
    restore.mutate(
      { revision: target.revision, baseRevision: base },
      {
        onSuccess: (draft) => {
          setTarget(null)
          onRestored(draft)
        },
        onError: (e) => setError(e.message),
      },
    )
  }

  return (
    <div className="flex h-full flex-col">
      <div className="border-border flex items-center justify-between border-b px-4 py-3">
        <h2 className="flex items-center gap-2 text-sm font-medium">
          <History className="size-4" /> History
        </h2>
        <Button variant="ghost" size="icon-sm" onClick={onClose} aria-label="Close panel">
          <X />
        </Button>
      </div>

      <div className="border-border flex gap-1 border-b px-3 py-2" role="tablist" aria-label="History">
        {(['versions', 'deployments'] as const).map((t) => (
          <button
            key={t}
            role="tab"
            aria-selected={tab === t}
            onClick={() => setTab(t)}
            className={cn('rounded-md px-3 py-1 text-sm font-medium capitalize', tab === t ? 'bg-muted text-foreground' : 'text-muted-foreground hover:text-foreground')}
          >
            {t}
          </button>
        ))}
      </div>

      <div className="flex-1 overflow-y-auto p-4">
        {tab === 'deployments' ? (
          <DeploymentsList deployments={deploymentList} />
        ) : versions.isPending ? (
          <div className="space-y-3">
            {[0, 1, 2].map((i) => (
              <Skeleton key={i} className="h-16 rounded-lg" />
            ))}
          </div>
        ) : versions.isError ? (
          <p role="alert" className="text-destructive text-sm">{versions.error.message}</p>
        ) : versions.data.length === 0 ? (
          <p className="text-muted-foreground text-sm text-pretty">
            No saved versions yet. Use <span className="text-foreground font-medium">Save version</span> to keep a snapshot you can come back to.
          </p>
        ) : (
          <ul className="space-y-3">
            {versions.data.map((v) => (
              <li key={v.revision} className="border-border rounded-lg border p-3">
                <div className="flex items-start justify-between gap-2">
                  <div className="min-w-0">
                    <div className="text-sm font-medium">Version {v.revision}</div>
                    {v.note && <div className="text-muted-foreground mt-0.5 text-sm break-words">{v.note}</div>}
                  </div>
                  <Button variant="outline" size="xs" onClick={() => { setError(null); setTarget(v) }}>
                    <RotateCcw /> Restore
                  </Button>
                </div>
                <div className="text-muted-foreground mt-2 text-xs">
                  {v.nodeCount} {v.nodeCount === 1 ? 'container' : 'containers'} · {v.edgeCount} {v.edgeCount === 1 ? 'connection' : 'connections'} · {timeAgo(v.createdAt)}
                </div>
              </li>
            ))}
          </ul>
        )}
      </div>

      <AlertDialog open={!!target} onOpenChange={(open) => !open && setTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Restore version {target?.revision}?</AlertDialogTitle>
            <AlertDialogDescription>
              The canvas will be replaced with this version. Your current design is overwritten unless you saved it as a version first.
            </AlertDialogDescription>
          </AlertDialogHeader>
          {error && <p role="alert" className="text-destructive text-xs">{error}</p>}
          <AlertDialogFooter>
            <Button variant="outline" onClick={() => setTarget(null)}>Cancel</Button>
            <Button onClick={confirmRestore} disabled={restore.isPending}>
              {restore.isPending && <Loader2 className="animate-spin" />}
              Restore
            </Button>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}

function DeploymentsList({ deployments }: { deployments: Deployment[] }) {
  if (deployments.length === 0) {
    return <p className="text-muted-foreground text-sm text-pretty">No deployments yet. Use <span className="text-foreground font-medium">Deploy</span> to run this design.</p>
  }
  return (
    <ul className="space-y-3">
      {deployments.map((d) => (
        <li key={d.id} className="border-border rounded-lg border p-3">
          <div className="flex items-center justify-between gap-2">
            <DeploymentBadge deployment={d} />
            <span className="text-muted-foreground text-xs">{timeAgo(d.createdAt)}</span>
          </div>
          <div className="text-muted-foreground mt-1.5 text-xs">
            Version {d.versionRevision ?? '?'}
            {d.tasksTotal > 0 && ` · ${d.tasksSucceeded}/${d.tasksTotal} containers`}
          </div>
          {d.error && d.state !== 'SUCCEEDED' && <div className="text-muted-foreground mt-1.5 text-xs break-words">{d.error}</div>}
        </li>
      ))}
    </ul>
  )
}
