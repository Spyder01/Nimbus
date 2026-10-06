import { Link, useParams } from 'react-router'
import { Skeleton } from '@/components/ui/skeleton'
import { buttonVariants } from '@/components/ui/button'
import { useApp } from '@/features/apps/queries'
import { CanvasEditor } from '@/features/canvas/editor'
import { ApiError } from '@/lib/api'

export default function AppCanvasPage() {
  const { id = '' } = useParams()
  const app = useApp(id)

  if (app.isPending) {
    return (
      <div className="space-y-4 p-6" aria-busy="true" aria-label="Loading app">
        <Skeleton className="h-10 w-72" />
        <Skeleton className="h-[60vh] rounded-2xl" />
      </div>
    )
  }

  if (app.isError) {
    const notFound = app.error instanceof ApiError && (app.error.status === 404 || app.error.status === 400)
    return (
      <div className="mx-auto max-w-md px-6 py-24 text-center">
        <h1 className="text-xl font-semibold">{notFound ? 'App not found' : "Couldn't load this app"}</h1>
        <p className="text-muted-foreground mt-2 text-sm">
          {notFound ? 'It may have been deleted, or the link is wrong.' : app.error.message}
        </p>
        <Link to="/dashboard" className={buttonVariants({ variant: 'outline' }) + ' mt-6'}>
          Back to apps
        </Link>
      </div>
    )
  }

  return <CanvasEditor key={app.data.id} app={app.data} />
}
