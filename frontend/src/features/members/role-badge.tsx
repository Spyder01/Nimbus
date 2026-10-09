import { ROLE_LABEL, type Role } from '@/lib/auth'
import { cn } from '@/lib/utils'

const TONE: Record<Role, string> = {
  USER: 'text-muted-foreground',
  ADMIN: 'text-info',
  SUPER_ADMIN: 'text-violet-500 dark:text-violet-300',
}

export function RoleBadge({ role, className }: { role: Role; className?: string }) {
  return <span className={cn('pill', TONE[role], className)}>{ROLE_LABEL[role]}</span>
}
