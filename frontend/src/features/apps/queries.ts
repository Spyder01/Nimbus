import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { request } from '@/lib/api'
import {
  isActive,
  type AppDetail,
  type AppSummary,
  type Deployment,
  type DeploymentList,
  type Draft,
  type GraphEdge,
  type GraphNode,
  type SaveVersionResult,
  type VersionSummary,
} from './types'

export const appsKey = ['apps'] as const
// Only the list: ['apps', id] shares the prefix and must not be refetched underneath an open canvas.
const listOnly = { queryKey: appsKey, exact: true } as const
const appKey = (id: string) => ['apps', id] as const
const versionsKey = (id: string) => ['apps', id, 'versions'] as const

// While any app has a queued/running deployment, keep the list fresh; otherwise stop polling.
const POLL_MS = 2000

export function useApps() {
  return useQuery({
    queryKey: appsKey,
    queryFn: () => request<AppSummary[]>('/api/apps'),
    refetchInterval: (q) => (q.state.data?.some((a) => isActive(a.activeDeployment)) ? POLL_MS : false),
  })
}

export function useApp(id: string) {
  return useQuery({
    queryKey: appKey(id),
    queryFn: () => request<AppDetail>(`/api/apps/${id}`),
    // The canvas owns the graph once loaded; never refetch underneath it, and always load fresh on entry.
    staleTime: Infinity,
    gcTime: 0,
    refetchOnWindowFocus: false,
    retry: false,
  })
}

export function useCreateApp() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (name: string) => request<AppDetail>('/api/apps', { method: 'POST', json: { name } }),
    onSuccess: () => qc.invalidateQueries(listOnly),
  })
}

export function useImportApp() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (v: { name: string; yaml: string }) => request<AppDetail>('/api/apps/import', { method: 'POST', json: v }),
    onSuccess: () => qc.invalidateQueries(listOnly),
  })
}

export function useRenameApp(id: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (name: string) => request<AppSummary>(`/api/apps/${id}`, { method: 'PATCH', json: { name } }),
    onSuccess: () => qc.invalidateQueries(listOnly),
  })
}

export function useDeleteApp() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => request<void>(`/api/apps/${id}`, { method: 'DELETE' }),
    onSuccess: () => qc.invalidateQueries(listOnly),
  })
}

export const saveDraft = (id: string, body: { nodes: GraphNode[]; edges: GraphEdge[]; baseRevision: number }) =>
  request<Draft>(`/api/apps/${id}/draft`, { method: 'PUT', json: body })

export function useVersions(id: string, enabled: boolean) {
  return useQuery({
    queryKey: versionsKey(id),
    queryFn: () => request<VersionSummary[]>(`/api/apps/${id}/versions`),
    enabled,
  })
}

export function useSaveVersion(id: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (note: string) =>
      request<SaveVersionResult>(`/api/apps/${id}/versions`, { method: 'POST', json: { note: note || null } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: versionsKey(id) }),
  })
}

export function useRestoreVersion(id: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (v: { revision: number; baseRevision: number }) =>
      request<Draft>(`/api/apps/${id}/versions/${v.revision}/restore`, {
        method: 'POST',
        json: { baseRevision: v.baseRevision },
      }),
    onSuccess: () => qc.invalidateQueries(listOnly),
  })
}

// ---- deployments ----

const deploymentsKey = (id: string) => ['apps', id, 'deployments'] as const

/**
 * Deployments of one app plus the app's state. Polls every 2s only while one is queued or running,
 * so an idle canvas makes no requests. `initial` fills in until the first response.
 */
export function useDeployments(id: string, initial?: DeploymentList) {
  return useQuery({
    queryKey: deploymentsKey(id),
    queryFn: () => request<DeploymentList>(`/api/apps/${id}/deployments`),
    placeholderData: initial,
    staleTime: 0,
    refetchOnWindowFocus: true,
    refetchInterval: (q) => (q.state.data?.deployments.some(isActive) ? POLL_MS : false),
  })
}

export function useDeploy(id: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () => request<Deployment>(`/api/apps/${id}/deployments`, { method: 'POST' }),
    onSettled: () => {
      void qc.invalidateQueries({ queryKey: deploymentsKey(id) })
      void qc.invalidateQueries(listOnly)
    },
  })
}

export function useCancelDeployment(appId: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (deploymentId: string) =>
      request<Deployment>(`/api/apps/${appId}/deployments/${deploymentId}/cancel`, { method: 'POST' }),
    onSettled: () => {
      void qc.invalidateQueries({ queryKey: deploymentsKey(appId) })
      void qc.invalidateQueries(listOnly)
    },
  })
}
