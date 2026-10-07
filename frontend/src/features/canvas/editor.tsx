import '@xyflow/react/dist/style.css'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import {
  addEdge,
  Background,
  BackgroundVariant,
  Controls,
  MarkerType,
  MiniMap,
  ReactFlow,
  ReactFlowProvider,
  useEdgesState,
  useNodesState,
  useReactFlow,
  type Connection,
  type NodeTypes,
} from '@xyflow/react'
import { AlertTriangle, ArrowLeft, Check, Copy, Download, History, Loader2, Plus, Rocket, Save, X } from 'lucide-react'
import { Link } from 'react-router'
import { useTheme } from '@/components/theme-provider'
import { Button, buttonVariants } from '@/components/ui/button'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { appsKey, useCancelDeployment, useDeploy, useDeployments } from '@/features/apps/queries'
import { StateBadge } from '@/features/apps/state-badge'
import { isActive, type AppDetail, type ContainerData, type Deployment, type Draft, type Problem, type SaveVersionResult } from '@/features/apps/types'
import { ApiError, request } from '@/lib/api'
import { downloadText, slug } from '@/lib/download'
import { cn } from '@/lib/utils'
import { ContainerNode } from './container-node'
import { defaultData, newId, TEMPLATES, toFlowEdges, toFlowNodes, uniqueName, type FlowEdge, type FlowNode, type Template } from './graph'
import { DeploymentOverlay } from './deployment-overlay'
import { HistoryPanel } from './history-panel'
import { Inspector } from './inspector'
import { ProblemsContext } from './problems-context'
import { RenameTitle } from './rename-title'
import { SaveVersionDialog } from './save-version-dialog'
import { useAutosave, type SaveStatus } from './use-autosave'
import { toYaml } from './yaml'

const nodeTypes: NodeTypes = { container: ContainerNode }
const edgeOptions = { type: 'smoothstep', animated: true, markerEnd: { type: MarkerType.ArrowClosed } }

export function CanvasEditor({ app }: { app: AppDetail }) {
  return (
    <ReactFlowProvider>
      <Editor app={app} />
    </ReactFlowProvider>
  )
}

function Editor({ app }: { app: AppDetail }) {
  const { theme } = useTheme()
  const { screenToFlowPosition, fitView, deleteElements } = useReactFlow<FlowNode, FlowEdge>()
  const wrapper = useRef<HTMLDivElement>(null)

  const [nodes, setNodes, onNodesChange] = useNodesState<FlowNode>(toFlowNodes(app.draft.nodes))
  const [edges, setEdges, onEdgesChange] = useEdgesState<FlowEdge>(toFlowEdges(app.draft.edges))
  const [name, setName] = useState(app.name)
  const [view, setView] = useState<'diagram' | 'yaml'>('diagram')
  const [historyOpen, setHistoryOpen] = useState(false)
  const [saveOpen, setSaveOpen] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)
  const noticeTimer = useRef<number | undefined>(undefined)

  const autosave = useAutosave(app.id, app.draft, nodes, edges)

  // Deployments: polled only while one is queued or running.
  const qc = useQueryClient()
  const deploy = useDeploy(app.id)
  const cancel = useCancelDeployment(app.id)
  const seed = [app.activeDeployment, app.latestDeployment].filter((d, i, a): d is Deployment => !!d && a.findIndex((x) => x?.id === d.id) === i)
  const deployments = useDeployments(app.id, { appState: app.state, deployments: seed })
  const deploymentList = deployments.data?.deployments ?? []
  const active = deploymentList.find(isActive) ?? null
  const latest = deploymentList[0] ?? null
  const appState = deployments.data?.appState ?? app.state
  const locked = !!active // the design can't be edited while a deployment is queued or running
  const [deployError, setDeployError] = useState<{ message: string; problems?: Problem[] } | null>(null)
  const [dismissed, setDismissed] = useState<string[]>([])
  const [finishedId, setFinishedId] = useState<string | null>(null)

  // When a deployment stops being active, remember it (for the result banner) and refresh the app list.
  const prevActive = useRef<string | null>(null)
  useEffect(() => {
    if (prevActive.current && !active) {
      setFinishedId(prevActive.current)
      void qc.invalidateQueries({ queryKey: appsKey, exact: true })
    }
    prevActive.current = active?.id ?? null
  }, [active, qc])

  const problemsByNode = useMemo(() => {
    const m = new Map<string, Problem[]>()
    for (const p of autosave.problems) if (p.nodeId) m.set(p.nodeId, [...(m.get(p.nodeId) ?? []), p])
    return m
  }, [autosave.problems])

  const selected = nodes.filter((n) => n.selected)
  const single = selected.length === 1 ? selected[0] : null

  const flash = (text: string) => {
    setNotice(text)
    window.clearTimeout(noticeTimer.current)
    noticeTimer.current = window.setTimeout(() => setNotice(null), 3500)
  }

  // Replace the canvas with a draft from the server (restore, or reload after a conflict).
  const applyDraft = useCallback(
    (draft: Draft) => {
      setNodes(toFlowNodes(draft.nodes))
      setEdges(toFlowEdges(draft.edges))
      autosave.reset(draft)
      window.setTimeout(() => fitView({ duration: 250, maxZoom: 1, padding: 0.3 }), 50)
    },
    [setNodes, setEdges, autosave, fitView],
  )

  async function reloadLatest() {
    const fresh = await request<AppDetail>(`/api/apps/${app.id}`)
    applyDraft(fresh.draft)
  }

  function patchNode(id: string, patch: Partial<ContainerData>) {
    setNodes((ns) => ns.map((n) => (n.id === id ? { ...n, data: { ...n.data, ...patch } } : n)))
  }

  function addContainer(t: Template) {
    const rect = wrapper.current?.getBoundingClientRect()
    const center = rect
      ? screenToFlowPosition({ x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 })
      : { x: 0, y: 0 }
    // First container: middle of the viewport. After that: to the right of the previous one so designs read
    // left to right, wrapping to a new row after four columns; then nudge until the spot is free.
    const W = 224
    const H = 110
    const GAP_X = 90
    const taken = (x: number, y: number) =>
      nodes.some((n) => Math.abs(n.position.x - x) < W + 16 && Math.abs(n.position.y - y) < H + 16)
    let x = center.x - W / 2
    let y = center.y - H / 2
    if (nodes.length > 0) {
      const last = nodes[nodes.length - 1]
      const minX = Math.min(...nodes.map((n) => n.position.x))
      const maxY = Math.max(...nodes.map((n) => n.position.y))
      x = last.position.x + W + GAP_X
      y = last.position.y
      if (x - minX > 3 * (W + GAP_X)) {
        x = minX
        y = maxY + H + 50
      }
    }
    for (let i = 0; i < 40 && taken(x, y); i++) y += H + 24
    const data = { ...defaultData(), ...t.data, name: uniqueName(t.base, nodes.map((n) => n.data.name)) }
    setNodes((ns) => [
      ...ns.map((n) => (n.selected ? { ...n, selected: false } : n)),
      { id: newId('n'), type: 'container', position: { x, y }, data, selected: true },
    ])
    setHistoryOpen(false)
    window.setTimeout(() => fitView({ duration: 300, maxZoom: 1, padding: 0.35 }), 60)
  }

  const onConnect = useCallback(
    (c: Connection) => {
      if (c.source === c.target) return
      setEdges((es) => addEdge({ ...c, id: newId('e') }, es))
    },
    [setEdges],
  )

  async function copyYaml() {
    try {
      await navigator.clipboard.writeText(toYaml(name, nodes, edges))
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1500)
    } catch {
      /* clipboard unavailable */
    }
  }

  async function deployNow() {
    setDeployError(null)
    // The deployment pins a saved version of the server's draft, so pending edits must reach it first.
    if (!(await autosave.flush())) {
      return setDeployError({ message: "Your latest edits couldn't be saved yet, so nothing was deployed." })
    }
    deploy.mutate(undefined, {
      onError: (e) =>
        setDeployError({
          message: e.message,
          problems: e instanceof ApiError ? ((e.body?.problems as Problem[] | undefined) ?? undefined) : undefined,
        }),
    })
  }

  const focusNode = (id: string) => setNodes((ns) => ns.map((n) => ({ ...n, selected: n.id === id })))
  const nodeName = (id: string | null) => nodes.find((n) => n.id === id)?.data.name || 'Unnamed'

  const panelOpen = historyOpen || (!!single && !locked)

  return (
    <div className="flex h-[calc(100dvh-6.25rem)] flex-col md:h-screen">
      {/* toolbar */}
      <div className="border-border flex flex-wrap items-center gap-x-2 gap-y-1 border-b px-3 py-2">
        <Link to="/dashboard" className={buttonVariants({ variant: 'ghost', size: 'icon' })} aria-label="Back to apps">
          <ArrowLeft />
        </Link>
        <RenameTitle appId={app.id} name={name} onRenamed={setName} />
        <StateBadge state={appState} deployment={active} className="hidden sm:inline-flex" />
        <SaveIndicator status={autosave.status} error={autosave.error} notice={notice} onRetry={() => void autosave.retry()} />

        <div className="ml-auto flex items-center gap-2">
          <div className="bg-muted/60 flex rounded-lg p-0.5 text-xs font-medium" role="tablist" aria-label="View">
            {(['diagram', 'yaml'] as const).map((v) => (
              <button
                key={v}
                role="tab"
                aria-selected={view === v}
                onClick={() => setView(v)}
                className={cn('rounded-md px-3 py-1', view === v ? 'bg-background text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground')}
              >
                {v === 'yaml' ? 'YAML' : 'Diagram'}
              </button>
            ))}
          </div>

          <DropdownMenu>
            <DropdownMenuTrigger render={<Button variant="outline" disabled={locked} />}>
              <Plus /> <span className="hidden sm:inline">Add container</span>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="w-56">
              {TEMPLATES.map((t) => (
                <DropdownMenuItem key={t.key} onClick={() => addContainer(t)} className="flex-col items-start gap-0">
                  <span>{t.label}</span>
                  <span className="text-muted-foreground text-xs">{t.hint}</span>
                </DropdownMenuItem>
              ))}
            </DropdownMenuContent>
          </DropdownMenu>

          <Button variant="outline" onClick={() => downloadText(`${slug(name)}.yaml`, toYaml(name, nodes, edges))} title="Export as YAML">
            <Download /> <span className="hidden sm:inline">Export</span>
          </Button>
          <Button variant={historyOpen ? 'secondary' : 'outline'} onClick={() => setHistoryOpen((o) => !o)} aria-pressed={historyOpen}>
            <History /> <span className="hidden sm:inline">History</span>
          </Button>
          <Button variant="outline" onClick={() => setSaveOpen(true)}>
            <Save /> <span className="hidden sm:inline">Save version</span>
          </Button>
          <Button onClick={() => void deployNow()} disabled={locked || deploy.isPending}>
            {deploy.isPending ? <Loader2 className="animate-spin" /> : <Rocket />} <span className="hidden sm:inline">Deploy</span>
          </Button>
        </div>
      </div>

      {autosave.status === 'conflict' && (
        <div role="alert" className="flex flex-wrap items-center gap-3 border-b border-amber-500/40 bg-amber-500/10 px-4 py-2 text-sm">
          <AlertTriangle className="size-4 text-amber-600 dark:text-amber-400" />
          <span className="flex-1">This app was changed somewhere else (another tab or window). Your edits here aren't saved.</span>
          <Button size="sm" variant="outline" onClick={() => void reloadLatest()}>Load latest</Button>
        </div>
      )}

      {deployError && (
        <div role="alert" className="border-destructive/30 bg-destructive/5 border-b px-4 py-2 text-sm">
          <div className="flex items-start gap-3">
            <AlertTriangle className="text-destructive mt-0.5 size-4 shrink-0" />
            <div className="min-w-0 flex-1">
              <div>{deployError.message}</div>
              {deployError.problems && (
                <ul className="mt-1 space-y-0.5">
                  {deployError.problems.slice(0, 6).map((p, i) => (
                    <li key={i} className="text-muted-foreground text-xs">
                      {p.nodeId ? (
                        <button type="button" className="text-foreground font-medium underline-offset-2 hover:underline" onClick={() => p.nodeId && focusNode(p.nodeId)}>
                          {nodeName(p.nodeId)}
                        </button>
                      ) : null}
                      {p.nodeId ? ': ' : ''}
                      {p.message}
                    </li>
                  ))}
                  {deployError.problems.length > 6 && <li className="text-muted-foreground text-xs">…and {deployError.problems.length - 6} more</li>}
                </ul>
              )}
            </div>
            <Button variant="ghost" size="icon-xs" aria-label="Dismiss" onClick={() => setDeployError(null)}><X /></Button>
          </div>
        </div>
      )}

      {latest && !active && !dismissed.includes(latest.id) && (latest.state === 'FAILED' || latest.id === finishedId) && (
        <ResultBanner
          deployment={latest}
          onDeployAgain={() => void deployNow()}
          onDismiss={() => setDismissed((d) => [...d, latest.id])}
        />
      )}

      {/* canvas + side panel */}
      <div className="relative flex min-h-0 flex-1">
        <div ref={wrapper} className="relative min-w-0 flex-1">
          <ProblemsContext.Provider value={problemsByNode}>
            <ReactFlow
              nodes={nodes}
              edges={edges}
              onNodesChange={onNodesChange}
              onEdgesChange={onEdgesChange}
              onConnect={onConnect}
              nodeTypes={nodeTypes}
              defaultEdgeOptions={edgeOptions}
              colorMode={theme}
              fitView
              fitViewOptions={{ maxZoom: 1, padding: 0.3 }}
              minZoom={0.3}
              deleteKeyCode={locked ? null : ['Backspace', 'Delete']}
              nodesDraggable={!locked}
              nodesConnectable={!locked}
              elementsSelectable={!locked}
            >
              <Background variant={BackgroundVariant.Dots} gap={22} size={1.5} />
              <Controls showInteractive={false} />
              <MiniMap pannable zoomable className="!hidden md:!block" />
            </ReactFlow>
          </ProblemsContext.Provider>

          {active && <DeploymentOverlay deployment={active} cancelling={cancel.isPending} onCancel={() => cancel.mutate(active.id)} />}

          {nodes.length === 0 && view === 'diagram' && !locked && <EmptyHint onPick={addContainer} />}

          {view === 'yaml' && (
            <div className="bg-background absolute inset-0 z-10 overflow-auto">
              <Button variant="outline" size="sm" className="absolute top-3 right-3" onClick={() => void copyYaml()}>
                {copied ? <Check /> : <Copy />} {copied ? 'Copied' : 'Copy'}
              </Button>
              <pre className="p-5 font-mono text-[13px] leading-6">{toYaml(name, nodes, edges)}</pre>
            </div>
          )}
        </div>

        {panelOpen && (
          <aside className="border-border bg-background z-20 shrink-0 border-l max-md:absolute max-md:inset-x-0 max-md:bottom-0 max-md:h-[62%] max-md:border-t max-md:border-l-0 md:w-80">
            {historyOpen ? (
              <HistoryPanel
                appId={app.id}
                deployments={deploymentList}
                onClose={() => setHistoryOpen(false)}
                onRestored={(d) => {
                  applyDraft(d)
                  flash('Version restored')
                }}
                prepareRestore={async () => ((await autosave.flush()) ? autosave.getRevision() : null)}
              />
            ) : (
              single && (
                <Inspector
                  key={single.id}
                  node={single}
                  problems={problemsByNode.get(single.id) ?? []}
                  onChange={(patch) => patchNode(single.id, patch)}
                  onDelete={() => void deleteElements({ nodes: [{ id: single.id }] })}
                  onClose={() => setNodes((ns) => ns.map((n) => ({ ...n, selected: false })))}
                />
              )
            )}
          </aside>
        )}
      </div>

      <SaveVersionDialog
        appId={app.id}
        open={saveOpen}
        onOpenChange={setSaveOpen}
        flush={autosave.flush}
        onSaved={(r: SaveVersionResult) =>
          flash(r.created ? `Saved version ${r.version.revision}` : `No changes since version ${r.version.revision}`)
        }
      />
    </div>
  )
}

function SaveIndicator({ status, error, notice, onRetry }: { status: SaveStatus; error: string | null; notice: string | null; onRetry: () => void }) {
  if (notice) {
    return (
      <span role="status" className="text-ok flex items-center gap-1 text-xs">
        <Check className="size-3.5" /> {notice}
      </span>
    )
  }
  switch (status) {
    case 'saving':
      return (
        <span role="status" className="text-muted-foreground flex items-center gap-1 text-xs">
          <Loader2 className="size-3.5 animate-spin" /> Saving…
        </span>
      )
    case 'dirty':
      return <span role="status" className="text-muted-foreground text-xs">Unsaved changes</span>
    case 'error':
      return (
        <span role="alert" className="text-destructive flex items-center gap-2 text-xs" title={error ?? undefined}>
          Couldn't save
          <Button variant="outline" size="xs" onClick={onRetry}>Retry</Button>
        </span>
      )
    case 'conflict':
      return <span className="text-xs text-amber-600 dark:text-amber-400">Out of date</span>
    default:
      return (
        <span role="status" className="text-muted-foreground flex items-center gap-1 text-xs">
          <Check className="size-3.5" /> Saved
        </span>
      )
  }
}

function EmptyHint({ onPick }: { onPick: (t: Template) => void }) {
  return (
    <div className="pointer-events-none absolute inset-0 z-[5] grid place-items-center p-4">
      <div className="border-border bg-card pointer-events-auto w-full max-w-sm rounded-2xl border p-6 text-center shadow-lg">
        <h2 className="font-medium">Add your first container</h2>
        <p className="text-muted-foreground mt-1 text-sm">Pick a starting point, or begin blank. Drag from one container to another to connect them: the arrow means “needs”, so what it points to is deployed first.</p>
        <div className="mt-5 grid grid-cols-2 gap-2">
          {TEMPLATES.map((t) => (
            <Button key={t.key} variant="outline" className="h-auto flex-col items-start gap-0 py-2 text-left" onClick={() => onPick(t)}>
              <span>{t.label}</span>
              <span className="text-muted-foreground text-[11px] font-normal">{t.hint}</span>
            </Button>
          ))}
        </div>
      </div>
    </div>
  )
}

function ResultBanner({ deployment, onDeployAgain, onDismiss }: { deployment: Deployment; onDeployAgain: () => void; onDismiss: () => void }) {
  const failed = deployment.state === 'FAILED'
  const ok = deployment.state === 'SUCCEEDED'
  return (
    <div
      role={failed ? 'alert' : 'status'}
      className={cn(
        'flex flex-wrap items-center gap-3 border-b px-4 py-2 text-sm',
        failed ? 'border-destructive/30 bg-destructive/5' : ok ? 'border-ok/30 bg-ok/10' : 'border-border bg-muted/40',
      )}
    >
      {failed ? <AlertTriangle className="text-destructive size-4" /> : ok ? <Check className="text-ok size-4" /> : null}
      <span className="min-w-0 flex-1">
        {failed
          ? `Deployment of version ${deployment.versionRevision ?? '?'} failed${deployment.error ? `: ${deployment.error}` : '.'}`
          : ok
            ? `Version ${deployment.versionRevision ?? '?'} deployed.`
            : 'Deployment cancelled.'}
      </span>
      {failed && (
        <Button size="sm" variant="outline" onClick={onDeployAgain}>
          Deploy again
        </Button>
      )}
      <Button variant="ghost" size="icon-xs" aria-label="Dismiss" onClick={onDismiss}>
        <X />
      </Button>
    </div>
  )
}
