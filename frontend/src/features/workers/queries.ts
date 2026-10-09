import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { request } from '@/lib/api'
import type { Pool, Worker, WorkerSettingsResponse } from './types'

// Workers send a heartbeat every ~10 s, so status and "last seen" are polled while the page is visible
// (React Query pauses polling in a background tab).
const POLL_MS = 5000

const workersKey = ['workers'] as const
const poolsKey = ['worker-pools'] as const

export function useWorkers() {
  return useQuery({
    queryKey: workersKey,
    queryFn: () => request<Worker[]>('/api/workers'),
    refetchInterval: POLL_MS,
  })
}

export function useWorker(name: string) {
  return useQuery({
    queryKey: [...workersKey, name],
    queryFn: () => request<Worker>(`/api/workers/${encodeURIComponent(name)}`),
    refetchInterval: POLL_MS,
  })
}

/** Replaces a worker's own settings: both fields are sent, and a null goes back to inheriting from the pool. */
export function useReplaceWorkerSettings(name: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (v: { parallelJobs: number | null; leaseSeconds: number | null }) =>
      request<WorkerSettingsResponse>(`/api/workers/${encodeURIComponent(name)}/settings`, { method: 'PUT', json: v }),
    onSuccess: () => qc.invalidateQueries({ queryKey: workersKey }),
  })
}

export function usePools() {
  return useQuery({
    queryKey: poolsKey,
    queryFn: () => request<Pool[]>('/api/worker-pools'),
    refetchInterval: POLL_MS,
  })
}

/**
 * Replaces a pool's default settings: both fields are sent, and a null goes back to each worker's own startup value.
 * Workers show the pool default they inherit, so their list is refreshed too.
 */
export function useReplacePoolDefaults(name: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (v: { parallelJobs: number | null; leaseSeconds: number | null }) =>
      request<Pool>(`/api/worker-pools/${encodeURIComponent(name)}/settings`, { method: 'PUT', json: v }),
    onSuccess: () => Promise.all([qc.invalidateQueries({ queryKey: poolsKey }), qc.invalidateQueries({ queryKey: workersKey })]),
  })
}
