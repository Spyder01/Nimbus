import { Check, CircleDashed, Clock, Loader2, Minus, X, XCircle } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { activeLabel } from '@/features/apps/state-badge'
import type { Deployment, DeploymentTask } from '@/features/apps/types'
import { timeAgo } from '@/lib/time'
import { cn } from '@/lib/utils'

// Covers the canvas while a deployment is queued or running: the design is locked until it finishes.
export function DeploymentOverlay({ deployment, cancelling, onCancel }: { deployment: Deployment; cancelling: boolean; onCancel: () => void }) {
  const queued = deployment.state === 'QUEUED'
  const stopping = deployment.cancelRequested
  return (
    <div className="bg-background/70 absolute inset-0 z-30 grid place-items-center p-4 backdrop-blur-[2px]" role="status" aria-live="polite">
      <div className="border-border bg-card w-full max-w-sm rounded-2xl border p-6 text-center shadow-xl">
        <Loader2 className="text-brand-to mx-auto size-8 animate-spin" />
        <h2 className="mt-4 font-medium">{activeLabel(deployment)}</h2>
        <p className="text-muted-foreground mt-1 text-sm text-pretty">
          {stopping
            ? 'Stopping as soon as the worker notices.'
            : queued
              ? 'Waiting for a worker to pick this up.'
              : `Deploying version ${deployment.versionRevision ?? ''}: ${deployment.tasksSucceeded} of ${deployment.tasksTotal} containers done.`}
        </p>
        <p className="text-muted-foreground mt-1 text-xs">
          {queued ? 'Queued' : 'Started'} {timeAgo(queued ? deployment.createdAt : (deployment.startedAt ?? deployment.createdAt))}
        </p>
        {deployment.tasks && deployment.tasks.length > 0 && <TaskList tasks={deployment.tasks} />}
        <div className="indeterminate bg-muted relative mx-auto mt-5 h-1 w-40 overflow-hidden rounded-full" aria-hidden="true" />
        <Button variant="outline" className="mt-5" onClick={onCancel} disabled={stopping || cancelling}>
          <XCircle /> {stopping ? 'Cancelling…' : 'Cancel deployment'}
        </Button>
        <p className="text-muted-foreground mt-3 text-xs">The design is locked until this finishes.</p>
      </div>
    </div>
  )
}

const TASK_ICON: Record<DeploymentTask['state'], { icon: typeof Check; className: string; label: string }> = {
  PENDING: { icon: Clock, className: 'text-muted-foreground', label: 'Waiting' },
  QUEUED: { icon: CircleDashed, className: 'text-muted-foreground', label: 'Ready' },
  IN_PROGRESS: { icon: Loader2, className: 'text-brand-to animate-spin', label: 'Deploying' },
  SUCCEEDED: { icon: Check, className: 'text-ok', label: 'Done' },
  FAILED: { icon: X, className: 'text-destructive', label: 'Failed' },
  CANCELLED: { icon: Minus, className: 'text-muted-foreground', label: 'Skipped' },
}

// One row per container, in deployment order, so you can see what is running and what it is waiting for.
function TaskList({ tasks }: { tasks: DeploymentTask[] }) {
  return (
    <ul className="border-border mt-5 max-h-48 space-y-1 overflow-y-auto rounded-lg border p-2 text-left text-sm">
      {tasks.map((t) => {
        const { icon: Icon, className, label } = TASK_ICON[t.state]
        return (
          <li key={t.id} className="flex items-center gap-2 px-1 py-0.5">
            <Icon className={cn('size-4 shrink-0', className)} aria-label={label} />
            <span className={cn('min-w-0 flex-1 truncate font-mono text-xs', t.state === 'CANCELLED' && 'text-muted-foreground line-through')}>{t.name}</span>
            {t.state === 'PENDING' && t.dependsOn.length > 0 && (
              <span className="text-muted-foreground truncate text-[11px]">needs {t.dependsOn.join(', ')}</span>
            )}
            {t.attempts > 1 && <span className="text-muted-foreground text-[11px]">attempt {t.attempts}</span>}
          </li>
        )
      })}
    </ul>
  )
}
