import { cn } from '@/lib/utils'

export function Avatar({ src, name, className }: { src?: string | null; name?: string | null; className?: string }) {
  return src ? (
    <img src={src} alt="" referrerPolicy="no-referrer" className={cn('rounded-full object-cover', className)} />
  ) : (
    <span
      aria-hidden="true"
      className={cn('bg-brand-gradient grid place-items-center rounded-full font-medium text-white', className)}
    >
      {(name || '?').charAt(0).toUpperCase()}
    </span>
  )
}
