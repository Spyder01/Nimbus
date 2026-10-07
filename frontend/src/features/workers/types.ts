// Mirrors the backend's worker DTOs (services/backend .../workers/dto).

export type WorkerStatus = 'ONLINE' | 'OFFLINE'

/** A null means "not set". */
export interface SettingValues {
  parallelJobs: number | null
  leaseSeconds: number | null
}

export interface WorkerSettingsView {
  /** Set on this worker alone; a null value inherits from the pool. */
  override: SettingValues
  poolDefault: SettingValues
  /** What applies: the override, else the pool default. A null means the worker's own startup value applies. */
  effective: SettingValues
  desiredVersion: number
  appliedVersion: number | null
  /** True once the worker has applied the current stored settings. */
  inSync: boolean
  problem: string | null
  overrideUpdatedAt: string | null
  overrideUpdatedBy: string | null
}

export interface Worker {
  name: string
  pool: string
  ordinal: number | null
  instanceId: string
  version: string | null
  host: string | null
  status: WorkerStatus
  startedAt: string
  lastSeenAt: string
  leaseExpiresAt: string
  heldJobs: number
  settings: WorkerSettingsView
}

export interface WorkerSettingsResponse {
  name: string
  pool: string
  settings: WorkerSettingsView
}

// Same limits as the backend, the worker and the database.
export const LIMITS = {
  parallelJobs: { min: 1, max: 100 },
  leaseSeconds: { min: 60, max: 86400 },
} as const
