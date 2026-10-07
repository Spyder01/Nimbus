import { AlertTriangle, Check, Loader2 } from 'lucide-react'
import { cn } from '@/lib/utils'
import type { Worker, WorkerSettingsView, WorkerStatus } from './types'

export function StatusBadge({ status }: { status: WorkerStatus }) {
  const online = status === 'ONLINE'
  return (
    <span
      className={cn(
        'border-border inline-flex items-center gap-1.5 rounded-full border px-2 py-0.5 text-xs font-medium',
        online ? 'text-ok' : 'text-muted-foreground',
      )}
    >
      <span className={cn('size-1.5 rounded-full', online ? 'bg-ok live-dot' : 'bg-muted-foreground/60')} />
      {online ? 'Online' : 'Offline'}
    </span>
  )
}

/** Has the worker caught up with its stored settings? */
export function SyncBadge({ settings, offline }: { settings: WorkerSettingsView; offline?: boolean }) {
  if (settings.problem) {
    return (
      <span className="text-destructive inline-flex items-center gap-1 text-xs font-medium" title={settings.problem}>
        <AlertTriangle className="size-3.5" /> Problem
      </span>
    )
  }
  if (settings.inSync) {
    return (
      <span className="text-ok inline-flex items-center gap-1 text-xs font-medium">
        <Check className="size-3.5" /> In sync
      </span>
    )
  }
  return (
    <span className="text-muted-foreground inline-flex items-center gap-1 text-xs font-medium">
      {!offline && <Loader2 className="size-3.5 animate-spin" />}
      {offline ? 'Pending (offline)' : 'Applying…'}
    </span>
  )
}

/** "in 24s", "5 min ago": short and precise enough for heartbeats and leases. */
export function relativeTime(iso: string): string {
  const s = Math.round((new Date(iso).getTime() - Date.now()) / 1000)
  const abs = Math.abs(s)
  const text = abs < 90 ? `${abs}s` : abs < 5400 ? `${Math.round(abs / 60)} min` : abs < 129600 ? `${Math.round(abs / 3600)} h` : `${Math.round(abs / 86400)} d`
  if (abs < 2) return 'just now'
  return s > 0 ? `in ${text}` : `${text} ago`
}

/** A setting's value, or what applies when it isn't set. */
export function settingText(value: number | null, unit: string, fallback: string): string {
  return value == null ? fallback : `${value}${unit}`
}

export function effectiveJobs(w: Worker): string {
  return settingText(w.settings.effective.parallelJobs, '', 'default')
}
