import { useContext } from 'react'
import { Handle, Position, type NodeProps } from '@xyflow/react'
import { AlertTriangle, Box, Database, Globe, HardDrive, Layers } from 'lucide-react'
import { cn } from '@/lib/utils'
import type { FlowNode } from './graph'
import { ProblemsContext } from './problems-context'

export function ContainerNode({ id, data, selected }: NodeProps<FlowNode>) {
  const problems = useContext(ProblemsContext).get(id)?.length ?? 0
  const stateful = data.kind === 'stateful'
  const autoscale = data.kind === 'stateless' && (data.minReplicas != null || data.maxReplicas != null)
  const Icon = stateful ? Database : Box

  return (
    <div
      className={cn(
        'bg-card border-border w-56 rounded-xl border shadow-sm transition-shadow',
        selected && 'ring-brand-to/60 shadow-md ring-2',
        problems > 0 && !selected && 'border-amber-500/60',
      )}
    >
      <Handle type="target" position={Position.Left} className="!bg-brand-to !border-background !size-3 !border-2" />

      <div className="flex items-center gap-2.5 p-3">
        <span className="bg-brand-gradient grid size-8 shrink-0 place-items-center rounded-lg text-white">
          <Icon className="size-4" />
        </span>
        <div className="min-w-0 flex-1">
          <div className={cn('truncate text-sm font-medium', !data.name && 'text-muted-foreground italic')}>
            {data.name || 'Unnamed'}
          </div>
          <div className={cn('truncate font-mono text-[11px]', data.image ? 'text-muted-foreground' : 'text-amber-600 dark:text-amber-400')}>
            {data.image || 'No image yet'}
          </div>
        </div>
        {problems > 0 && (
          <span
            title={`${problems} thing${problems === 1 ? '' : 's'} to fix`}
            className="flex items-center gap-0.5 text-xs font-medium text-amber-600 dark:text-amber-400"
          >
            <AlertTriangle className="size-3.5" />
            {problems}
          </span>
        )}
      </div>

      <div className="text-muted-foreground border-border flex items-center gap-3 border-t px-3 py-2 text-[11px]">
        <span className="flex items-center gap-1" title="Replicas">
          <Layers className="size-3" />
          {autoscale ? `${data.minReplicas ?? '?'}–${data.maxReplicas ?? '?'}` : `×${data.replicas}`}
        </span>
        {data.port != null && <span className="font-mono">:{data.port}</span>}
        {data.expose && (
          <span className="flex items-center gap-1" title="Public">
            <Globe className="size-3" /> public
          </span>
        )}
        {stateful && data.volume && (
          <span className="ml-auto flex items-center gap-1" title={`Volume at ${data.volume.mountPath}`}>
            <HardDrive className="size-3" />
            {data.volume.size}
          </span>
        )}
      </div>

      <Handle type="source" position={Position.Right} className="!bg-brand-to !border-background !size-3 !border-2" />
    </div>
  )
}
