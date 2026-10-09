import { useState } from 'react'
import { Boxes, MoreHorizontal, Plus, Trash2, Upload, XCircle } from 'lucide-react'
import { Link } from 'react-router'
import { Button } from '@/components/ui/button'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { Skeleton } from '@/components/ui/skeleton'
import { CreateAppDialog } from '@/features/apps/create-app-dialog'
import { DeleteAppDialog } from '@/features/apps/delete-app-dialog'
import { ImportAppDialog } from '@/features/apps/import-app-dialog'
import { useApps, useCancelDeployment } from '@/features/apps/queries'
import { PublicUrlList } from '@/features/apps/public-links'
import { StateBadge } from '@/features/apps/state-badge'
import { isActive, type AppSummary } from '@/features/apps/types'
import { timeAgo } from '@/lib/time'

export default function DashboardPage() {
  const apps = useApps()
  const [creating, setCreating] = useState(false)
  const [importing, setImporting] = useState(false)
  const [deleting, setDeleting] = useState<AppSummary | null>(null)

  return (
    <div className="mx-auto max-w-5xl">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">Apps</h1>
          <p className="text-muted-foreground mt-1.5 text-sm">Design an app on the canvas, then run it on Kubernetes.</p>
        </div>
        <div className="flex shrink-0 gap-2">
          <Button variant="outline" onClick={() => setImporting(true)}>
            <Upload /> Import YAML
          </Button>
          <Button onClick={() => setCreating(true)}>
            <Plus /> Create app
          </Button>
        </div>
      </div>

      <div className="mt-8">
        {apps.isPending ? (
          <Loading />
        ) : apps.isError ? (
          <ErrorState message={apps.error.message} onRetry={() => apps.refetch()} />
        ) : apps.data.length === 0 ? (
          <EmptyState onCreate={() => setCreating(true)} />
        ) : (
          <ul className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {apps.data.map((app) => (
              <AppCard key={app.id} app={app} onDelete={() => setDeleting(app)} />
            ))}
          </ul>
        )}
      </div>

      <CreateAppDialog open={creating} onOpenChange={setCreating} />
      <ImportAppDialog open={importing} onOpenChange={setImporting} />
      <DeleteAppDialog app={deleting} onClose={() => setDeleting(null)} />
    </div>
  )
}

function AppCard({ app, onDelete }: { app: AppSummary; onDelete: () => void }) {
  const cancel = useCancelDeployment(app.id)
  const active = isActive(app.activeDeployment) ? app.activeDeployment : null
  return (
    <li className="card-surface hover:border-ring/50 group relative overflow-hidden rounded-2xl p-5 transition-all duration-200 hover:-translate-y-0.5 hover:shadow-[var(--shadow-pop)]">
      <div className="flex items-start justify-between gap-2">
        <span className="bg-muted text-muted-foreground group-hover:text-foreground border-border grid size-9 place-items-center rounded-lg border transition-colors">
          <Boxes className="size-[18px]" />
        </span>
        <DropdownMenu>
          <DropdownMenuTrigger
            render={<Button variant="ghost" size="icon-sm" className="relative z-10" aria-label={`Actions for ${app.name}`} />}
          >
            <MoreHorizontal />
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end">
            {active && (
              <DropdownMenuItem disabled={active.cancelRequested || cancel.isPending} onClick={() => cancel.mutate(active.id)}>
                <XCircle /> {active.cancelRequested ? 'Cancelling…' : 'Cancel deployment'}
              </DropdownMenuItem>
            )}
            <DropdownMenuItem variant="destructive" disabled={!!active} onClick={onDelete}>
              <Trash2 /> {active ? 'Delete (cancel deployment first)' : 'Delete'}
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>

      {/* stretched link: the whole card opens the app, the menu above stays clickable */}
      <h2 className="mt-4 truncate font-medium tracking-tight">
        <Link to={`/apps/${app.id}`} className="after:absolute after:inset-0 after:rounded-2xl focus-visible:outline-none">
          {app.name}
        </Link>
      </h2>
      <div className="text-muted-foreground mt-1 text-sm">
        {app.componentCount} {app.componentCount === 1 ? 'container' : 'containers'}
      </div>
      <div className="mt-4 flex items-center justify-between gap-2">
        <StateBadge state={app.state} deployment={active} />
        <span className="text-muted-foreground text-xs">Updated {timeAgo(app.updatedAt)}</span>
      </div>
      {/* above the stretched link, so the card still opens the app everywhere else */}
      {app.publicUrls.length > 0 && (
        <div className="border-border relative z-10 mt-3 border-t pt-3">
          <PublicUrlList urls={app.publicUrls} max={3} />
        </div>
      )}
      {active && <div className="indeterminate bg-muted absolute inset-x-0 bottom-0 h-0.5 overflow-hidden" aria-hidden="true" />}
    </li>
  )
}

function Loading() {
  return (
    <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3" aria-busy="true" aria-label="Loading apps">
      {[0, 1, 2].map((i) => (
        <Skeleton key={i} className="h-40 rounded-2xl" />
      ))}
    </div>
  )
}

function EmptyState({ onCreate }: { onCreate: () => void }) {
  return (
    <div className="border-border flex flex-col items-center rounded-2xl border border-dashed px-6 py-20 text-center">
      <span className="bg-brand-gradient mb-5 grid size-12 place-items-center rounded-xl text-white">
        <Boxes className="size-6" />
      </span>
      <h2 className="text-lg font-medium">No applications yet</h2>
      <p className="text-muted-foreground mt-1.5 max-w-sm text-sm text-pretty">
        Create your first application, then add containers on the canvas.
      </p>
      <Button className="mt-6" onClick={onCreate}>
        <Plus /> Create application
      </Button>
    </div>
  )
}

function ErrorState({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div role="alert" className="border-destructive/30 bg-destructive/5 rounded-2xl border px-6 py-10 text-center">
      <p className="font-medium">Couldn't load your apps</p>
      <p className="text-muted-foreground mt-1 text-sm">{message}</p>
      <Button variant="outline" className="mt-5" onClick={onRetry}>
        Try again
      </Button>
    </div>
  )
}
