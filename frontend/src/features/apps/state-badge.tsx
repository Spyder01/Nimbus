import { Loader2 } from 'lucide-react'
import { cn } from '@/lib/utils'
import { isActive, type AppState, type Deployment, type DeploymentState } from './types'

const STYLES: Record<AppState, { label: string; dot: string; text: string; pulse?: boolean }> = {
  DRAFT: { label: 'Draft', dot: 'bg-muted-foreground/60', text: 'text-muted-foreground' },
  DEPLOYING: { label: 'Deploying', dot: 'bg-info', text: 'text-info', pulse: true },
  RUNNING: { label: 'Running', dot: 'bg-ok', text: 'text-ok' },
  DEGRADED: { label: 'Degraded', dot: 'bg-warn', text: 'text-warn' },
  FAILED: { label: 'Failed', dot: 'bg-destructive', text: 'text-destructive' },
  STOPPED: { label: 'Stopped', dot: 'bg-muted-foreground/60', text: 'text-muted-foreground' },
  DELETING: { label: 'Deleting', dot: 'bg-destructive', text: 'text-destructive', pulse: true },
}

/** What a queued/running deployment looks like to the user. */
export function activeLabel(d: Deployment): string {
  if (d.cancelRequested) return 'Cancelling…'
  if (d.state === 'QUEUED') return 'Queued'
  return d.tasksTotal > 0 ? `Deploying ${d.tasksSucceeded}/${d.tasksTotal}` : 'Deploying…'
}

/** The app's state; while a deployment is active it shows that instead, with a spinner. */
export function StateBadge({ state, deployment, className }: { state: AppState; deployment?: Deployment | null; className?: string }) {
  const s = STYLES[state]
  const busy = isActive(deployment)
  return (
    <span
      className={cn('pill', busy ? STYLES.DEPLOYING.text : s.text, className)}
    >
      {busy ? <Loader2 className="size-3 animate-spin" /> : <span className={cn('size-1.5 rounded-full', s.dot, s.pulse && 'live-dot')} />}
      {busy ? activeLabel(deployment) : s.label}
    </span>
  )
}

const DEPLOYMENT: Record<DeploymentState, { label: string; text: string }> = {
  QUEUED: { label: 'Queued', text: 'text-muted-foreground' },
  IN_PROGRESS: { label: 'In progress', text: 'text-info' },
  SUCCEEDED: { label: 'Succeeded', text: 'text-ok' },
  FAILED: { label: 'Failed', text: 'text-destructive' },
  CANCELLED: { label: 'Cancelled', text: 'text-muted-foreground' },
}

export function DeploymentBadge({ deployment, className }: { deployment: Deployment; className?: string }) {
  const s = DEPLOYMENT[deployment.state]
  return (
    <span className={cn('inline-flex items-center gap-1.5 text-xs font-medium', s.text, className)}>
      {isActive(deployment) && <Loader2 className="size-3 animate-spin" />}
      {deployment.cancelRequested && isActive(deployment) ? 'Cancelling…' : s.label}
    </span>
  )
}
