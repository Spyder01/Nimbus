import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { request } from '@/lib/api'
import type { Worker, WorkerSettingsResponse } from './types'

// Workers send a heartbeat every ~10 s, so status and "last seen" are polled while the page is visible
// (React Query pauses polling in a background tab).
const POLL_MS = 5000

const workersKey = ['workers'] as const

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
