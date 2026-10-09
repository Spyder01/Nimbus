import { useState } from 'react'
import { ApiError } from '@/lib/api'
import { LIMITS, type SettingValues } from './types'

function parse(text: string, min: number, max: number, what: string): { value: number | null; error?: string } {
  const t = text.trim()
  if (t === '') return { value: null }
  if (!/^\d+$/.test(t)) return { value: null, error: `${what} must be a whole number` }
  const n = Number(t)
  if (n < min || n > max) return { value: null, error: `${what} must be between ${min} and ${max}` }
  return { value: n }
}

/** The state of the two settings inputs: what was typed, what it means, and whether it is acceptable. */
export function useSettingsFields(initial: SettingValues) {
  const [jobs, setJobs] = useState(initial.parallelJobs?.toString() ?? '')
  const [lease, setLease] = useState(initial.leaseSeconds?.toString() ?? '')
  const pj = parse(jobs, LIMITS.parallelJobs.min, LIMITS.parallelJobs.max, 'Parallel jobs')
  const ls = parse(lease, LIMITS.leaseSeconds.min, LIMITS.leaseSeconds.max, 'Lease length')
  const valid = !pj.error && !ls.error
  return {
    jobs, lease, setJobs, setLease,
    jobsError: pj.error, leaseError: ls.error, valid,
    values: { parallelJobs: pj.value, leaseSeconds: ls.value },
    /** Nothing to save: what is typed is what is already stored. */
    unchangedFrom: (stored: SettingValues) => valid && pj.value === stored.parallelJobs && ls.value === stored.leaseSeconds,
  }
}

/** What the server said was wrong with a save, as a list under the error message. */
export function saveProblems(error: unknown): string[] {
  if (!(error instanceof ApiError)) return []
  return ((error.body?.errors as { message: string }[] | undefined) ?? []).map((p) => p.message)
}
