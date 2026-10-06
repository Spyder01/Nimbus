// Mirrors the backend DTOs (services/backend .../apps/dto).

export type AppState = 'DRAFT' | 'DEPLOYING' | 'RUNNING' | 'DEGRADED' | 'FAILED' | 'STOPPED' | 'DELETING'

export type EnvVar = { key: string; value?: string | null; secret?: boolean }
export type Volume = { size: string; mountPath: string }

// `type` (not interface) so it satisfies React Flow's Record<string, unknown> node data constraint.
export type ContainerData = {
  name: string
  image: string
  port: number | null
  expose: boolean
  kind: 'stateless' | 'stateful'
  replicas: number
  minReplicas: number | null
  maxReplicas: number | null
  cpuTarget: number | null
  env: EnvVar[]
  volume: Volume | null
}

export type GraphNode = {
  id: string
  type: 'container'
  position: { x: number; y: number }
  data: ContainerData
}

export type GraphEdge = { id: string; source: string; target: string; data?: Record<string, unknown> }

export type Problem = { nodeId: string | null; field: string | null; message: string }

export interface Draft {
  revision: number
  nodes: GraphNode[]
  edges: GraphEdge[]
  problems: Problem[]
  updatedAt: string | null
}

export type DeploymentState = 'QUEUED' | 'IN_PROGRESS' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'

export type TaskState = 'PENDING' | 'QUEUED' | 'IN_PROGRESS' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'

/** One container's part of a deployment. */
export interface DeploymentTask {
  id: string
  nodeId: string
  name: string
  /** 0 = deployed first. */
  ordinal: number
  /** Tasks in the same layer don't depend on each other and run in parallel. */
  layer: number
  state: TaskState
  attempts: number
  error: string | null
  /** Names of the containers this one waits for. */
  dependsOn: string[]
  startedAt: string | null
  finishedAt: string | null
}

export interface Deployment {
  id: string
  appId: string
  /** The saved version this deployment runs. */
  versionRevision: number | null
  state: DeploymentState
  /** Cancel was requested while running; it ends as CANCELLED once the running tasks stop. */
  cancelRequested: boolean
  error: string | null
  requestedBy: string | null
  createdAt: string | null
  startedAt: string | null
  finishedAt: string | null
  tasksTotal: number
  tasksSucceeded: number
  /** Per-container progress; present while active and when fetching a single deployment. */
  tasks: DeploymentTask[] | null
}

export interface DeploymentList {
  appState: AppState
  deployments: Deployment[]
}

export const isActive = (d: Deployment | null | undefined): d is Deployment =>
  !!d && (d.state === 'QUEUED' || d.state === 'IN_PROGRESS')

export interface AppSummary {
  id: string
  name: string
  state: AppState
  componentCount: number
  createdAt: string | null
  updatedAt: string | null
  /** The queued or running deployment, if any. */
  activeDeployment: Deployment | null
}

export interface AppDetail {
  id: string
  name: string
  state: AppState
  createdAt: string | null
  updatedAt: string | null
  draft: Draft
  activeDeployment: Deployment | null
  latestDeployment: Deployment | null
}

export interface VersionSummary {
  revision: number
  note: string | null
  nodeCount: number
  edgeCount: number
  createdBy: string | null
  createdAt: string | null
}

export interface SaveVersionResult {
  created: boolean
  version: VersionSummary
}
