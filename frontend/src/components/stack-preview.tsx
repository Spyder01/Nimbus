import { useId, useState } from 'react'
import { Box, Database, Globe, Layers, Network, Zap, type LucideIcon } from 'lucide-react'
import { cn } from '@/lib/utils'
import { useTypedCount } from '@/lib/use-typewriter'

type NodeId = 'ingress' | 'web' | 'api' | 'redis' | 'worker' | 'postgres'

interface StackNode {
  id: NodeId
  label: string
  sub: string
  icon: LucideIcon
  x: number // percent of canvas
  y: number
}

const NODES: StackNode[] = [
  { id: 'ingress', label: 'ingress', sub: 'acme.app', icon: Globe, x: 15, y: 50 },
  { id: 'web', label: 'web', sub: 'next:14', icon: Layers, x: 46, y: 17 },
  { id: 'api', label: 'api', sub: 'go:2.1', icon: Box, x: 46, y: 62 },
  { id: 'worker', label: 'worker', sub: 'py:0.9', icon: Network, x: 82, y: 17 },
  { id: 'redis', label: 'redis', sub: '7.2', icon: Zap, x: 82, y: 50 },
  { id: 'postgres', label: 'postgres', sub: '16', icon: Database, x: 82, y: 83 },
]

const EDGES: [NodeId, NodeId][] = [
  ['ingress', 'web'],
  ['ingress', 'api'],
  ['web', 'api'],
  ['api', 'redis'],
  ['api', 'postgres'],
  ['redis', 'worker'],
]

const byId = Object.fromEntries(NODES.map((n) => [n.id, n])) as Record<NodeId, StackNode>

function edgePath(a: StackNode, b: StackNode) {
  const mx = (a.x + b.x) / 2
  return `M ${a.x} ${a.y} C ${mx} ${a.y}, ${mx} ${b.y}, ${b.x} ${b.y}`
}

const MAX_REPLICAS = 8

function yamlFor(replicas: number) {
  return [
    'app: acme-shop',
    'services:',
    '  web:',
    '    image: ghcr.io/acme/web:14',
    '    expose: true',
    '  api:',
    '    image: ghcr.io/acme/api:2.1',
    `    replicas: ${replicas}`,
    '    needs: [redis, postgres]',
    '  worker:',
    '    image: ghcr.io/acme/worker:0.9',
    '    needs: [redis]',
    '  redis:',
    '    image: redis:7.2',
    '  postgres:',
    '    image: postgres:16',
    '    volume: 10Gi',
  ]
}

function YamlLine({ line }: { line: string }) {
  const indent = line.length - line.trimStart().length
  const body = line.trimStart()
  const i = body.indexOf(':')
  if (i === -1) return <>{line}</>
  const key = body.slice(0, i)
  const value = body.slice(i + 1)
  return (
    <>
      {' '.repeat(indent)}
      <span className="text-brand-to dark:text-brand-from">{key}</span>:
      <span className={/^\s*\d+$/.test(value) ? 'text-ok' : 'text-muted-foreground'}>{value}</span>
    </>
  )
}

export function StackPreview() {
  const [view, setView] = useState<'diagram' | 'yaml'>('diagram')
  const [replicas, setReplicas] = useState(3)
  const sliderId = useId()
  const yamlLines = yamlFor(replicas)
  const yamlTotal = yamlLines.join('\n').length
  const typed = useTypedCount(yamlTotal, view)

  // one pod each for web, worker, redis and postgres, plus the api replicas
  const totalPods = replicas + 4

  return (
    <div className="border-border bg-surface relative overflow-hidden rounded-2xl border shadow-xl shadow-black/5 backdrop-blur-xl dark:shadow-black/40">
      {/* window chrome */}
      <div className="border-border flex items-center justify-between border-b px-4 py-2.5">
        <div className="flex items-center gap-1.5" aria-hidden="true">
          <span className="bg-muted-foreground/30 size-2.5 rounded-full" />
          <span className="bg-muted-foreground/30 size-2.5 rounded-full" />
          <span className="bg-muted-foreground/30 size-2.5 rounded-full" />
        </div>
        <div className="bg-muted/60 flex rounded-lg p-0.5 text-xs font-medium" role="tablist">
          {(['diagram', 'yaml'] as const).map((v) => (
            <button
              key={v}
              role="tab"
              aria-selected={view === v}
              onClick={() => setView(v)}
              className={cn(
                'rounded-md px-3 py-1 capitalize transition-colors',
                view === v ? 'bg-background text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground',
              )}
            >
              {v === 'yaml' ? 'YAML' : 'Diagram'}
            </button>
          ))}
        </div>
        <div className="text-muted-foreground flex items-center gap-1.5 text-xs">
          <span className="bg-ok live-dot size-1.5 rounded-full" />
          <span className="tabular-nums">{totalPods} pods</span>
        </div>
      </div>

      {/* canvas */}
      <div className="relative h-[22rem] sm:h-[28rem] lg:h-[30rem]">
        {view === 'diagram' ? (
          <div className="absolute inset-0">
            <svg
              className="absolute inset-0 size-full"
              viewBox="0 0 100 100"
              preserveAspectRatio="none"
              aria-hidden="true"
            >
              {EDGES.map(([a, b], i) => (
                <path
                  key={`${a}-${b}`}
                  d={edgePath(byId[a], byId[b])}
                  fill="none"
                  strokeWidth={1.5}
                  vectorEffect="non-scaling-stroke"
                  className="edge-flow edge-in stroke-brand-to/50 dark:stroke-brand-from/60"
                  style={{ animationDelay: `${450 + i * 90}ms, 0ms` }}
                />
              ))}
            </svg>

            {NODES.map((n, idx) => {
              const Icon = n.icon
              const isApi = n.id === 'api'
              return (
                <div
                  key={n.id}
                  style={{ left: `${n.x}%`, top: `${n.y}%`, animationDelay: `${idx * 90}ms` }}
                  className={cn(
                    'node-in border-border bg-card absolute w-[6.25rem] rounded-xl border p-2 shadow-sm sm:w-36 sm:p-3',
                    isApi && 'ring-brand-to/40 ring-2',
                  )}
                >
                  <div className="flex items-center gap-1.5">
                    <span
                      className={cn(
                        'grid size-5 shrink-0 place-items-center rounded-md sm:size-7 sm:rounded-lg',
                        n.id === 'postgres' || n.id === 'redis' ? 'bg-violet-500/12 text-violet-500 dark:text-violet-300' : 'bg-info/12 text-info',
                      )}
                    >
                      <Icon className="size-3 sm:size-4" />
                    </span>
                    <span className="truncate text-xs font-medium sm:text-sm">{n.label}</span>
                  </div>
                  <div className="text-muted-foreground mt-1 flex items-center justify-between text-[10px] sm:mt-2 sm:text-xs">
                    <span className="truncate">{n.sub}</span>
                    {isApi && <span className="tabular-nums">×{replicas}</span>}
                  </div>
                  {isApi && (
                    <div className="mt-1.5 flex flex-wrap gap-[3px] sm:mt-2.5 sm:gap-1" aria-hidden="true">
                      {Array.from({ length: replicas }, (_, i) => (
                        <span key={i} className="pod-pop bg-ok size-1.5 rounded-[2px] sm:size-2" />
                      ))}
                    </div>
                  )}
                </div>
              )
            })}
          </div>
        ) : (
          <pre className="absolute inset-0 overflow-auto px-5 py-4 font-mono text-[12.5px] leading-[1.35rem]">
            {yamlLines
              .join('\n')
              .slice(0, typed)
              .split('\n')
              .map((line, i, all) => (
                <div key={i}>
                  <YamlLine line={line} />
                  {i === all.length - 1 && typed < yamlTotal && <span className="caret" />}
                </div>
              ))}
          </pre>
        )}
      </div>

      {/* scale control */}
      <div className="border-border flex items-center gap-4 border-t px-4 py-3">
        <label htmlFor={sliderId} className="text-sm font-medium whitespace-nowrap">
          Scale <span className="text-muted-foreground font-normal">api</span>
        </label>
        <input
          id={sliderId}
          type="range"
          min={1}
          max={MAX_REPLICAS}
          value={replicas}
          onChange={(e) => setReplicas(Number(e.target.value))}
          className="accent-brand-to h-1.5 flex-1 cursor-pointer"
        />
        <span className="w-14 text-right text-sm tabular-nums">
          {replicas} {replicas === 1 ? 'pod' : 'pods'}
        </span>
      </div>
    </div>
  )
}
