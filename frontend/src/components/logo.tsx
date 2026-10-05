import { Cloud } from 'lucide-react'

export function Logo({ iconOnly = false }: { iconOnly?: boolean }) {
  return (
    <div className="flex items-center gap-2.5">
      <span className="bg-brand-gradient grid size-8 place-items-center rounded-[10px] text-white shadow-sm">
        <Cloud className="size-[18px]" fill="currentColor" strokeWidth={1.5} />
      </span>
      <span className={iconOnly ? 'sr-only' : 'text-lg font-semibold tracking-tight'}>Nimbus</span>
    </div>
  )
}
