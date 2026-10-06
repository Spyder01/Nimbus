import type { Edge, Node } from '@xyflow/react'
import type { ContainerData, Draft, GraphEdge, GraphNode } from '@/features/apps/types'

export type FlowNode = Node<ContainerData, 'container'>
export type FlowEdge = Edge

// Field order matches the backend DTO so a fresh node serialises identically to one loaded from the server.
export const defaultData = (): ContainerData => ({
  name: '',
  image: '',
  port: null,
  expose: false,
  kind: 'stateless',
  replicas: 1,
  minReplicas: null,
  maxReplicas: null,
  cpuTarget: null,
  env: [],
  volume: null,
})

export const newId = (prefix: string) => `${prefix}${crypto.randomUUID().replace(/-/g, '').slice(0, 10)}`

export const toFlowNodes = (nodes: GraphNode[]): FlowNode[] =>
  nodes.map((n) => ({ id: n.id, type: 'container', position: n.position, data: n.data }))

export const toFlowEdges = (edges: GraphEdge[]): FlowEdge[] =>
  edges.map((e) => ({ id: e.id, source: e.source, target: e.target }))

const round = (n: number) => Math.round(n * 100) / 100

// What the backend stores: React Flow's runtime fields (selected, measured, ...) are dropped.
export function toGraph(nodes: FlowNode[], edges: FlowEdge[]) {
  return {
    nodes: nodes.map((n) => ({
      id: n.id,
      type: 'container' as const,
      position: { x: round(n.position.x), y: round(n.position.y) },
      data: n.data,
    })),
    edges: edges.map((e) => ({ id: e.id, source: e.source, target: e.target, data: {} })),
  }
}

export const graphJson = (nodes: FlowNode[], edges: FlowEdge[]) => JSON.stringify(toGraph(nodes, edges))

export const draftJson = (d: Pick<Draft, 'nodes' | 'edges'>) =>
  graphJson(toFlowNodes(d.nodes), toFlowEdges(d.edges))

export function uniqueName(base: string, taken: Iterable<string>): string {
  const used = new Set(taken)
  if (!used.has(base)) return base
  for (let i = 2; ; i++) if (!used.has(`${base}-${i}`)) return `${base}-${i}`
}

export interface Template {
  key: string
  label: string
  hint: string
  base: string
  data: Partial<ContainerData>
}

export const TEMPLATES: Template[] = [
  { key: 'blank', label: 'Blank container', hint: 'Start from scratch', base: 'service', data: {} },
  { key: 'web', label: 'Web server', hint: 'nginx, public', base: 'web', data: { image: 'nginx:1.27', port: 80, expose: true } },
  {
    key: 'postgres',
    label: 'PostgreSQL',
    hint: 'Stateful, 10Gi volume',
    base: 'postgres',
    data: {
      image: 'postgres:16',
      port: 5432,
      kind: 'stateful',
      env: [{ key: 'POSTGRES_PASSWORD', secret: true }],
      volume: { size: '10Gi', mountPath: '/var/lib/postgresql/data' },
    },
  },
  {
    key: 'redis',
    label: 'Redis',
    hint: 'Stateful, 1Gi volume',
    base: 'redis',
    data: { image: 'redis:7.2', port: 6379, kind: 'stateful', volume: { size: '1Gi', mountPath: '/data' } },
  },
]
