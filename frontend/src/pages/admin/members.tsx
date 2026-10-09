import { useEffect, useState } from 'react'
import { Loader2, Search, ShieldCheck, ShieldOff, Users } from 'lucide-react'
import { Avatar } from '@/components/avatar'
import { useSession } from '@/components/session-provider'
import {
  AlertDialog,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogMedia,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { RoleBadge } from '@/features/members/role-badge'
import { PAGE_SIZE, useMembers, useSetMemberRole } from '@/features/members/queries'
import type { Member } from '@/features/members/types'
import { isSuperAdmin } from '@/lib/auth'
import { timeAgo } from '@/lib/time'

const PROVIDER: Record<string, string> = { GITHUB: 'GitHub', GOOGLE: 'Google' }

export default function AdminMembersPage() {
  const { me } = useSession()
  const canChangeRoles = isSuperAdmin(me)
  const [text, setText] = useState('')
  const [q, setQ] = useState('')
  const [page, setPage] = useState(0)
  const [changing, setChanging] = useState<Member | null>(null)
  const members = useMembers(q, page)

  // Search once typing pauses, and start again from the first page.
  useEffect(() => {
    const t = window.setTimeout(() => {
      setQ(text.trim())
      setPage(0)
    }, 300)
    return () => window.clearTimeout(t)
  }, [text])

  const data = members.data
  const from = data && data.total > 0 ? data.page * data.size + 1 : 0
  const to = data ? data.page * data.size + data.items.length : 0

  return (
    <div className="mx-auto max-w-6xl">
      <h1 className="text-2xl font-semibold tracking-tight">Members</h1>
      <p className="text-muted-foreground mt-1 text-sm">
        Everyone with an account.{' '}
        {canChangeRoles ? 'As a super admin you can make a member an admin, or take it away.' : 'Only a super admin can change who is an admin.'}
      </p>

      <div className="relative mt-8 max-w-sm">
        <Search className="text-muted-foreground pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2" />
        <Input
          value={text}
          onChange={(e) => setText(e.target.value)}
          placeholder="Search by name, email or username"
          aria-label="Search members"
          className="h-9 pl-8"
        />
      </div>

      <div className="mt-4">
        {members.isPending ? (
          <Loading />
        ) : members.isError ? (
          <ErrorState message={members.error.message} onRetry={() => members.refetch()} />
        ) : data && data.total === 0 ? (
          <EmptyState query={q} />
        ) : data ? (
          <>
            <div className="card-surface overflow-x-auto rounded-2xl">
              <table className="w-full min-w-[46rem] text-sm">
                <thead>
                  <tr className="text-muted-foreground border-border bg-muted/40 border-b text-left text-xs">
                    <th className="px-4 py-3 font-medium">Member</th>
                    <th className="px-4 py-3 font-medium">Signs in with</th>
                    <th className="px-4 py-3 font-medium">Role</th>
                    <th className="px-4 py-3 font-medium">Joined</th>
                    {canChangeRoles && <th className="px-4 py-3" />}
                  </tr>
                </thead>
                <tbody className="divide-border divide-y">
                  {data.items.map((m) => (
                    <Row key={m.id} member={m} isSelf={m.id === me?.id} canChangeRoles={canChangeRoles} onChange={() => setChanging(m)} />
                  ))}
                </tbody>
              </table>
            </div>

            <div className="text-muted-foreground mt-3 flex items-center justify-between text-xs">
              <span aria-live="polite">
                {from}–{to} of {data.total}
              </span>
              <div className="flex gap-2">
                <Button variant="outline" size="sm" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
                  Previous
                </Button>
                <Button variant="outline" size="sm" disabled={to >= data.total} onClick={() => setPage((p) => p + 1)}>
                  Next
                </Button>
              </div>
            </div>
          </>
        ) : null}
      </div>

      <ChangeRoleDialog member={changing} onClose={() => setChanging(null)} />
    </div>
  )
}

function Row({ member: m, isSelf, canChangeRoles, onChange }: { member: Member; isSelf: boolean; canChangeRoles: boolean; onChange: () => void }) {
  const label = m.name ?? m.login ?? 'Unnamed'
  // A super admin's role isn't changed here, and nobody changes their own.
  const changeable = canChangeRoles && !isSelf && m.role !== 'SUPER_ADMIN'
  return (
    <tr className="hover:bg-muted/40 transition-colors">
      <td className="px-4 py-3">
        <div className="flex items-center gap-3">
          <Avatar src={m.avatarUrl} name={label} className="size-8 shrink-0 text-sm" />
          <div className="min-w-0">
            <div className="truncate font-medium">
              {label}
              {isSelf && <span className="text-muted-foreground ml-2 text-xs font-normal">you</span>}
            </div>
            <div className="text-muted-foreground max-w-[18rem] truncate text-xs">{m.email ?? 'No email'}</div>
          </div>
        </div>
      </td>
      <td className="px-4 py-3">
        {m.provider ? (
          <span>
            {PROVIDER[m.provider] ?? m.provider}
            {m.login && <span className="text-muted-foreground font-mono text-xs"> @{m.login}</span>}
          </span>
        ) : (
          <span className="text-muted-foreground">—</span>
        )}
      </td>
      <td className="px-4 py-3">
        <RoleBadge role={m.role} />
      </td>
      <td className="text-muted-foreground px-4 py-3 whitespace-nowrap">{timeAgo(m.createdAt)}</td>
      {canChangeRoles && (
        <td className="px-4 py-3 text-right">
          {changeable && (
            <Button variant="outline" size="sm" onClick={onChange} aria-label={`${m.role === 'ADMIN' ? 'Remove admin from' : 'Make admin'} ${label}`}>
              {m.role === 'ADMIN' ? <ShieldOff /> : <ShieldCheck />}
              {m.role === 'ADMIN' ? 'Remove admin' : 'Make admin'}
            </Button>
          )}
        </td>
      )}
    </tr>
  )
}

// `member` null = closed.
function ChangeRoleDialog({ member, onClose }: { member: Member | null; onClose: () => void }) {
  return (
    <AlertDialog open={!!member} onOpenChange={(open) => !open && onClose()}>
      <AlertDialogContent>{member && <ChangeRoleForm key={member.id} member={member} onClose={onClose} />}</AlertDialogContent>
    </AlertDialog>
  )
}

function ChangeRoleForm({ member, onClose }: { member: Member; onClose: () => void }) {
  const setRole = useSetMemberRole()
  const name = member.name ?? member.login ?? 'this member'
  const demoting = member.role === 'ADMIN'

  return (
    <div className="grid gap-4">
      <AlertDialogHeader>
        <AlertDialogMedia className={demoting ? 'bg-destructive/10 text-destructive' : 'bg-info/12 text-info'}>
          {demoting ? <ShieldOff /> : <ShieldCheck />}
        </AlertDialogMedia>
        <AlertDialogTitle>{demoting ? `Remove admin from ${name}?` : `Make ${name} an admin?`}</AlertDialogTitle>
        <AlertDialogDescription className="text-pretty">
          {demoting
            ? 'They lose access to the admin pages straight away, even if they are signed in right now.'
            : 'They can see the admin pages, including the workers and this member list, and change worker settings. It takes effect straight away. They can’t make anyone else an admin.'}
        </AlertDialogDescription>
      </AlertDialogHeader>
      {setRole.isError && (
        <p role="alert" className="text-destructive text-sm">
          {setRole.error.message}
        </p>
      )}
      <AlertDialogFooter>
        <Button type="button" variant="outline" onClick={onClose}>
          Cancel
        </Button>
        <Button
          type="button"
          variant={demoting ? 'destructive' : 'default'}
          disabled={setRole.isPending}
          onClick={() => setRole.mutate({ id: member.id, role: demoting ? 'USER' : 'ADMIN' }, { onSuccess: onClose })}
        >
          {setRole.isPending && <Loader2 className="animate-spin" />}
          {demoting ? 'Remove admin' : 'Make admin'}
        </Button>
      </AlertDialogFooter>
    </div>
  )
}

function Loading() {
  return (
    <div className="space-y-2" aria-busy="true" aria-label="Loading members">
      {Array.from({ length: Math.min(PAGE_SIZE, 6) }, (_, i) => (
        <Skeleton key={i} className="h-14 rounded-xl" />
      ))}
    </div>
  )
}

function EmptyState({ query }: { query: string }) {
  return (
    <div className="border-border flex flex-col items-center rounded-2xl border border-dashed px-6 py-16 text-center">
      <span className="bg-muted text-muted-foreground mb-4 grid size-11 place-items-center rounded-xl">
        <Users className="size-5" />
      </span>
      <h2 className="font-medium">{query ? `No members match “${query}”` : 'No members yet'}</h2>
      {query && <p className="text-muted-foreground mt-1 text-sm">Try a name, an email address or a username.</p>}
    </div>
  )
}

function ErrorState({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div role="alert" className="border-destructive/30 bg-destructive/5 rounded-2xl border px-6 py-10 text-center">
      <p className="font-medium">Couldn't load the members</p>
      <p className="text-muted-foreground mt-1 text-sm">{message}</p>
      <Button variant="outline" className="mt-5" onClick={onRetry}>
        Try again
      </Button>
    </div>
  )
}
