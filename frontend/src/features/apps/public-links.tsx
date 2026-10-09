import { ChevronDown, ExternalLink } from 'lucide-react'
import { buttonVariants } from '@/components/ui/button'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { cn } from '@/lib/utils'
import type { PublicUrl } from './types'

const host = (url: string) => url.replace(/^https?:\/\//, '')

/**
 * Opens a running app's public containers in a new tab: a button when there is one address, a menu when there are
 * several. Shows nothing when there are none.
 */
export function PublicLinks({ urls, className }: { urls: PublicUrl[]; className?: string }) {
  if (urls.length === 0) return null
  const style = cn(buttonVariants({ variant: 'outline', size: 'sm' }), className)

  if (urls.length === 1) {
    const [u] = urls
    return (
      <a href={u.url} target="_blank" rel="noopener noreferrer" className={style} title={u.url}>
        <ExternalLink /> Open
      </a>
    )
  }
  return (
    <DropdownMenu>
      <DropdownMenuTrigger className={style}>
        <ExternalLink /> Open <ChevronDown className="opacity-60" />
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="min-w-56">
        {urls.map((u) => (
          <DropdownMenuItem key={u.container} render={<a href={u.url} target="_blank" rel="noopener noreferrer" />} className="flex-col items-start gap-0">
            <span>{u.container}</span>
            <span className="text-muted-foreground font-mono text-xs">{host(u.url)}</span>
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

/** The addresses themselves, as visible links: one row per public container, so nobody has to open a menu to find them. */
export function PublicUrlList({ urls, className, max }: { urls: PublicUrl[]; className?: string; max?: number }) {
  if (urls.length === 0) return null
  const shown = max ? urls.slice(0, max) : urls
  const hidden = urls.length - shown.length
  return (
    <ul className={cn('space-y-1', className)} aria-label="Public addresses">
      {shown.map((u) => (
        <li key={u.container} className="flex min-w-0 items-baseline gap-2 text-sm">
          <span className="text-muted-foreground shrink-0 text-xs">{u.container}</span>
          <a
            href={u.url}
            target="_blank"
            rel="noopener noreferrer"
            className="text-info inline-flex min-w-0 items-center gap-1 font-mono text-xs hover:underline"
            title={u.url}
          >
            <span className="truncate">{host(u.url)}</span>
            <ExternalLink className="size-3 shrink-0" />
          </a>
        </li>
      ))}
      {hidden > 0 && <li className="text-muted-foreground text-xs">and {hidden} more</li>}
    </ul>
  )
}
