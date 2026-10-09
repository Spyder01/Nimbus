import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import type { useSettingsFields } from './use-settings-fields'
import { LIMITS } from './types'

type Fields = ReturnType<typeof useSettingsFields>

/** The two settings inputs with their limits and problems. An empty one means "inherit". */
export function SettingsFields({ fields, idPrefix, jobsPlaceholder, leasePlaceholder }: {
  fields: Fields
  idPrefix: string
  jobsPlaceholder: string
  leasePlaceholder: string
}) {
  return (
    <div className="grid gap-5 sm:grid-cols-2">
      <div className="grid gap-1.5">
        <Label htmlFor={`${idPrefix}-jobs`}>Parallel jobs</Label>
        <Input
          id={`${idPrefix}-jobs`}
          inputMode="numeric"
          className="h-10"
          placeholder={jobsPlaceholder}
          value={fields.jobs}
          onChange={(e) => fields.setJobs(e.target.value)}
          aria-invalid={!!fields.jobsError}
        />
        <p className={fields.jobsError ? 'text-destructive text-xs' : 'text-muted-foreground text-xs'}>
          {fields.jobsError ?? `${LIMITS.parallelJobs.min}–${LIMITS.parallelJobs.max}. Lowering it doesn't stop jobs that are already running.`}
        </p>
      </div>
      <div className="grid gap-1.5">
        <Label htmlFor={`${idPrefix}-lease`}>Lease length (seconds)</Label>
        <Input
          id={`${idPrefix}-lease`}
          inputMode="numeric"
          className="h-10"
          placeholder={leasePlaceholder}
          value={fields.lease}
          onChange={(e) => fields.setLease(e.target.value)}
          aria-invalid={!!fields.leaseError}
        />
        <p className={fields.leaseError ? 'text-destructive text-xs' : 'text-muted-foreground text-xs'}>
          {fields.leaseError ?? `${LIMITS.leaseSeconds.min}–${LIMITS.leaseSeconds.max}. Applies to jobs claimed after the change.`}
        </p>
      </div>
    </div>
  )
}
