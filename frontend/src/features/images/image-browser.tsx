import { useMemo, useState } from 'react'
import { Database, Search } from 'lucide-react'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { searchImages, type CatalogImage } from './catalog'
import { useImageCatalog } from './queries'

interface Props {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Called with the chosen image and tag. The dialog closes itself afterwards. */
  onSelect: (image: CatalogImage, tag: string) => void
}

/** Search and browse the whole catalog, by category, and pick an image with the tag you want. */
export function ImageBrowser({ open, onOpenChange, onSelect }: Props) {
  const [query, setQuery] = useState('')
  const [category, setCategory] = useState<string | null>(null)
  const catalog = useImageCatalog()
  const images = catalog.data?.images
  const categories = catalog.data?.categories ?? []
  const results = useMemo(() => (images ? searchImages(images, query, category) : []), [images, query, category])

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="flex max-h-[85vh] flex-col gap-0 p-0 sm:max-w-3xl">
        <DialogHeader className="border-border border-b p-4 pr-12">
          <DialogTitle>Browse images</DialogTitle>
          <DialogDescription>Pick from the catalog, or close this and type any image name yourself.</DialogDescription>
          <div className="relative mt-2">
            <Search className="text-muted-foreground pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2" />
            <Input
              autoFocus
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Search by name, e.g. postgres, queue, dashboard"
              aria-label="Search images"
              className="h-9 pl-8"
            />
          </div>
          <div className="mt-2 flex flex-wrap gap-1.5" role="group" aria-label="Category">
            <Chip active={category === null} onClick={() => setCategory(null)}>
              All
            </Chip>
            {categories.map((c) => (
              <Chip key={c} active={category === c} onClick={() => setCategory(c)}>
                {c}
              </Chip>
            ))}
          </div>
        </DialogHeader>

        <div className="min-h-0 flex-1 overflow-y-auto p-2">
          {catalog.isPending ? (
            <div className="space-y-2 p-2" aria-busy="true" aria-label="Loading images">
              {[0, 1, 2, 3].map((i) => (
                <Skeleton key={i} className="h-16 rounded-lg" />
              ))}
            </div>
          ) : catalog.isError ? (
            <div role="alert" className="px-4 py-14 text-center text-sm">
              <p className="font-medium">Couldn't load the image catalog</p>
              <p className="text-muted-foreground mt-1">You can still close this and type any image name yourself.</p>
              <Button variant="outline" className="mt-4" onClick={() => void catalog.refetch()}>
                Try again
              </Button>
            </div>
          ) : results.length === 0 ? (
            <div className="text-muted-foreground px-4 py-14 text-center text-sm">
              <p className="text-foreground font-medium">No images match “{query}”.</p>
              <p className="mt-1">Not in the catalog? Close this and type the image name, such as <span className="font-mono">ghcr.io/acme/api:2.1</span>.</p>
            </div>
          ) : (
            <ul className="space-y-1">
              {results.map((e) => (
                <Row
                  key={e.id}
                  entry={e}
                  onSelect={(tag) => {
                    onSelect(e, tag)
                    onOpenChange(false)
                  }}
                />
              ))}
            </ul>
          )}
        </div>
        <div className="border-border text-muted-foreground border-t px-4 py-2 text-xs">
          {catalog.isSuccess ? `${results.length} ${results.length === 1 ? 'image' : 'images'}` : '\u00a0'}
        </div>
      </DialogContent>
    </Dialog>
  )
}

function Chip({ active, onClick, children }: { active: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      type="button"
      aria-pressed={active}
      onClick={onClick}
      className={cn(
        'rounded-full border px-2.5 py-1 text-xs font-medium transition-colors',
        active ? 'border-foreground bg-foreground text-background' : 'border-border text-muted-foreground hover:text-foreground hover:bg-muted',
      )}
    >
      {children}
    </button>
  )
}

function Row({ entry: e, onSelect }: { entry: CatalogImage; onSelect: (tag: string) => void }) {
  const [tag, setTag] = useState(e.defaultTag)
  const stateful = e.kind === 'stateful'
  return (
    <li className="hover:bg-muted/60 group flex items-start gap-3 rounded-lg p-2.5 transition-colors">
      <span
        className={cn(
          'mt-0.5 grid size-9 shrink-0 place-items-center rounded-lg text-sm font-semibold',
          stateful ? 'bg-violet-500/12 text-violet-500 dark:text-violet-300' : 'bg-info/12 text-info',
        )}
        aria-hidden="true"
      >
        {stateful ? <Database className="size-4" /> : e.name.charAt(0).toUpperCase()}
      </span>

      <button type="button" onClick={() => onSelect(tag)} className="min-w-0 flex-1 text-left outline-none focus-visible:underline">
        <span className="flex flex-wrap items-baseline gap-x-2">
          <span className="font-medium">{e.name}</span>
          <span className="text-muted-foreground truncate font-mono text-xs">{e.image}</span>
        </span>
        <span className="text-muted-foreground mt-0.5 line-clamp-2 block text-xs">{e.description}</span>
        <span className="text-muted-foreground mt-1.5 flex flex-wrap gap-1.5 text-[11px]">
          <Meta>{e.category}</Meta>
          {e.port != null && <Meta mono>:{e.port}</Meta>}
          <Meta>{stateful ? `Stateful${e.volume ? ` · ${e.volume.size}` : ''}` : 'Stateless'}</Meta>
          {e.env?.some((v) => v.secret) && <Meta>Needs a secret</Meta>}
        </span>
      </button>

      <div className="flex shrink-0 items-center gap-2">
        <label className="sr-only" htmlFor={`tag-${e.id}`}>
          Tag for {e.name}
        </label>
        <select
          id={`tag-${e.id}`}
          value={tag}
          onChange={(ev) => setTag(ev.target.value)}
          className="border-input bg-background h-8 max-w-32 rounded-lg border px-2 font-mono text-xs outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
        >
          {e.tags.map((t) => (
            <option key={t} value={t}>
              {t}
            </option>
          ))}
        </select>
      </div>
    </li>
  )
}

function Meta({ children, mono }: { children: React.ReactNode; mono?: boolean }) {
  return <span className={cn('bg-muted rounded px-1.5 py-0.5', mono && 'font-mono')}>{children}</span>
}
