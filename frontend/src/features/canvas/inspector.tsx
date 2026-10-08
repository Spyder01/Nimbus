import { useState } from 'react'
import { Plus, Trash2, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Switch } from '@/components/ui/switch'
import type { ContainerData, EnvVar, Problem } from '@/features/apps/types'
import { applyEntry } from '@/features/images/catalog'
import { ImagePicker } from '@/features/images/image-picker'
import { cn } from '@/lib/utils'
import type { FlowNode } from './graph'

interface Props {
  node: FlowNode
  problems: Problem[]
  onChange: (patch: Partial<ContainerData>) => void
  onDelete: () => void
  onClose: () => void
}

export function Inspector({ node, problems, onChange, onDelete, onClose }: Props) {
  const d = node.data
  const stateful = d.kind === 'stateful'
  const autoscale = !stateful && (d.minReplicas != null || d.maxReplicas != null || d.cpuTarget != null)
  const msgs = (field: string) => problems.filter((p) => p.field === field).map((p) => p.message)

  function setKind(kind: ContainerData['kind']) {
    if (kind === d.kind) return
    if (kind === 'stateful') {
      onChange({ kind, minReplicas: null, maxReplicas: null, cpuTarget: null, volume: d.volume ?? { size: '10Gi', mountPath: '/data' } })
    } else {
      onChange({ kind, volume: null })
    }
  }

  function setAutoscale(on: boolean) {
    if (on) onChange({ minReplicas: 1, maxReplicas: Math.max(d.replicas, 3), cpuTarget: 70 })
    else onChange({ minReplicas: null, maxReplicas: null, cpuTarget: null })
  }

  const setEnv = (i: number, patch: Partial<EnvVar>) =>
    onChange({ env: d.env.map((e, j) => (j === i ? { ...e, ...patch } : e)) })

  return (
    <div className="flex h-full flex-col">
      <div className="border-border flex items-center justify-between border-b px-4 py-3">
        <h2 className="text-sm font-medium">Container</h2>
        <Button variant="ghost" size="icon-sm" onClick={onClose} aria-label="Close panel">
          <X />
        </Button>
      </div>

      <div className="flex-1 space-y-6 overflow-y-auto p-4">
        {msgs('connections').map((m) => (
          <p key={m} role="alert" className="border-destructive/30 bg-destructive/5 text-destructive rounded-lg border px-3 py-2 text-xs">
            {m}
          </p>
        ))}

        <Field label="Name" id="c-name" errors={msgs('name')}>
          <Input id="c-name" value={d.name} onChange={(e) => onChange({ name: e.target.value })} placeholder="api" className="font-mono" aria-invalid={msgs('name').length > 0} />
        </Field>

        <Field label="Image" id="c-image" errors={msgs('image')}>
          <ImagePicker
            id="c-image"
            value={d.image}
            invalid={msgs('image').length > 0}
            onChange={(image) => onChange({ image })}
            onPick={(entry, tag) => onChange(applyEntry(d, entry, tag))}
          />
        </Field>

        <div className="space-y-2">
          <Label>Type</Label>
          <div className="bg-muted/60 grid grid-cols-2 gap-1 rounded-lg p-1" role="radiogroup" aria-label="Container type">
            {(['stateless', 'stateful'] as const).map((k) => (
              <button
                key={k}
                type="button"
                role="radio"
                aria-checked={d.kind === k}
                onClick={() => setKind(k)}
                className={cn(
                  'rounded-md px-3 py-1.5 text-sm font-medium capitalize transition-colors',
                  d.kind === k ? 'bg-background text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground',
                )}
              >
                {k}
              </button>
            ))}
          </div>
          <p className="text-muted-foreground text-xs">
            {stateful ? 'Keeps its data on a volume, with a fixed number of replicas.' : 'Replicas are interchangeable and can autoscale.'}
          </p>
        </div>

        <div className="grid grid-cols-2 gap-3">
          <Field label="Port" id="c-port" errors={msgs('port')}>
            <NumField id="c-port" value={d.port} nullable min={1} max={65535} placeholder="8080" onChange={(port) => onChange({ port })} />
          </Field>
          <Field label="Replicas" id="c-replicas" errors={msgs('replicas')}>
            <NumField id="c-replicas" value={d.replicas} min={1} max={1000} onChange={(replicas) => onChange({ replicas: replicas ?? 1 })} />
          </Field>
        </div>

        <ToggleRow label="Public" hint="Reachable from the internet" checked={d.expose} onChange={(expose) => onChange({ expose })} />

        {!stateful && (
          <div className="space-y-3">
            <ToggleRow label="Autoscale" hint="Add or remove replicas with load" checked={autoscale} onChange={setAutoscale} />
            {autoscale && (
              <div className="grid grid-cols-3 gap-3">
                <Field label="Min" id="c-min" errors={msgs('minReplicas')}>
                  <NumField id="c-min" value={d.minReplicas} nullable min={0} max={1000} onChange={(minReplicas) => onChange({ minReplicas })} />
                </Field>
                <Field label="Max" id="c-max" errors={msgs('maxReplicas')}>
                  <NumField id="c-max" value={d.maxReplicas} nullable min={1} max={1000} onChange={(maxReplicas) => onChange({ maxReplicas })} />
                </Field>
                <Field label="CPU %" id="c-cpu" errors={msgs('cpuTarget')}>
                  <NumField id="c-cpu" value={d.cpuTarget} nullable min={1} max={100} onChange={(cpuTarget) => onChange({ cpuTarget })} />
                </Field>
              </div>
            )}
          </div>
        )}

        {stateful && (
          <section className="space-y-3">
            <h3 className="text-sm font-medium">Volume</h3>
            {msgs('volume').map((m) => (
              <p key={m} className="text-destructive text-xs">{m}</p>
            ))}
            <div className="grid grid-cols-[6rem_1fr] gap-3">
              <Field label="Size" id="c-vol-size" errors={msgs('volume.size')}>
                <Input id="c-vol-size" value={d.volume?.size ?? ''} placeholder="10Gi" className="font-mono" aria-invalid={msgs('volume.size').length > 0}
                  onChange={(e) => onChange({ volume: { size: e.target.value, mountPath: d.volume?.mountPath ?? '' } })} />
              </Field>
              <Field label="Mount path" id="c-vol-path" errors={msgs('volume.mountPath')}>
                <Input id="c-vol-path" value={d.volume?.mountPath ?? ''} placeholder="/data" className="font-mono" aria-invalid={msgs('volume.mountPath').length > 0}
                  onChange={(e) => onChange({ volume: { size: d.volume?.size ?? '', mountPath: e.target.value } })} />
              </Field>
            </div>
          </section>
        )}

        <section className="space-y-3">
          <div className="flex items-center justify-between">
            <h3 className="text-sm font-medium">Environment</h3>
            <Button variant="ghost" size="sm" onClick={() => onChange({ env: [...d.env, { key: '', value: '' }] })}>
              <Plus /> Add
            </Button>
          </div>
          {d.env.length === 0 && <p className="text-muted-foreground text-xs">No variables.</p>}
          {d.env.map((e, i) => {
            const keyErrors = msgs(`env[${i}].key`)
            return (
              <div key={i} className="space-y-1">
                <div className="grid grid-cols-[1fr_1fr_auto_auto] items-center gap-2">
                  <Input aria-label="Variable name" value={e.key} placeholder="KEY" className="font-mono text-xs" aria-invalid={keyErrors.length > 0}
                    onChange={(ev) => setEnv(i, { key: ev.target.value })} />
                  <Input aria-label="Variable value" value={e.secret ? '' : (e.value ?? '')} disabled={e.secret}
                    placeholder={e.secret ? 'Set at deploy' : 'value'} className="font-mono text-xs"
                    onChange={(ev) => setEnv(i, { value: ev.target.value })} />
                  <Switch size="sm" checked={!!e.secret} aria-label="Secret"
                    onCheckedChange={(secret) => setEnv(i, secret ? { secret: true, value: null } : { secret: false, value: '' })} />
                  <Button variant="ghost" size="icon-xs" aria-label="Remove variable" onClick={() => onChange({ env: d.env.filter((_, j) => j !== i) })}>
                    <X />
                  </Button>
                </div>
                {keyErrors.map((m) => (
                  <p key={m} className="text-destructive text-xs">{m}</p>
                ))}
              </div>
            )
          })}
          {d.env.some((e) => e.secret) && (
            <p className="text-muted-foreground text-xs">Secret values are never stored in the design; you'll enter them when deploying.</p>
          )}
        </section>
      </div>

      <div className="border-border border-t p-4">
        <Button variant="destructive" className="w-full" onClick={onDelete}>
          <Trash2 /> Delete container
        </Button>
      </div>
    </div>
  )
}

function Field({ label, id, errors, children }: { label: string; id: string; errors: string[]; children: React.ReactNode }) {
  return (
    <div className="space-y-1.5">
      <Label htmlFor={id}>{label}</Label>
      {children}
      {errors.map((m) => (
        <p key={m} className="text-destructive text-xs">{m}</p>
      ))}
    </div>
  )
}

function ToggleRow({ label, hint, checked, onChange }: { label: string; hint: string; checked: boolean; onChange: (v: boolean) => void }) {
  return (
    <div className="flex items-center justify-between gap-4">
      <div>
        <div className="text-sm font-medium">{label}</div>
        <div className="text-muted-foreground text-xs">{hint}</div>
      </div>
      <Switch checked={checked} onCheckedChange={onChange} aria-label={label} />
    </div>
  )
}

// Number input that lets the field be empty while typing; only valid numbers reach `onChange`.
function NumField({ id, value, onChange, nullable = false, min, max, placeholder }: {
  id: string
  value: number | null
  onChange: (v: number | null) => void
  nullable?: boolean
  min: number
  max: number
  placeholder?: string
}) {
  const [text, setText] = useState(value == null ? '' : String(value))
  // The value can also change from outside (picking an image fills in its port): follow it, but leave what the user
  // is typing alone while it still means the same number.
  const [seen, setSeen] = useState(value)
  if (value !== seen) {
    setSeen(value)
    if (value == null ? text !== '' : Number(text) !== value || text === '') setText(value == null ? '' : String(value))
  }
  return (
    <Input
      id={id}
      type="number"
      inputMode="numeric"
      min={min}
      max={max}
      placeholder={placeholder}
      value={text}
      onChange={(e) => {
        const raw = e.target.value
        setText(raw)
        if (raw === '') return nullable ? onChange(null) : undefined
        const n = Number(raw)
        if (Number.isInteger(n) && n >= min && n <= max) onChange(n)
      }}
      onBlur={() => setText(value == null ? '' : String(value))}
    />
  )
}
