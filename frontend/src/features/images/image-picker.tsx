import { useId, useMemo, useState } from 'react'
import { LayoutGrid } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { cn } from '@/lib/utils'
import { findImage, imageRef, popularImages, searchImages, splitRef, type CatalogImage } from './catalog'
import { ImageBrowser } from './image-browser'
import { useImageCatalog } from './queries'

interface Props {
  id: string
  value: string
  invalid?: boolean
  /** The text of the field changed (the user typed, or picked just a tag). */
  onChange: (image: string) => void
  /** The user picked a catalog image: the parent decides what else to fill in. */
  onPick: (image: CatalogImage, tag: string) => void
}

type Option = { key: string; entry?: CatalogImage; tag?: string; label: string; sub?: string; category?: string }

const MAX = 7

/**
 * The image field. It is still a plain text box, so any image from any registry can be typed. While it has focus it
 * suggests catalog images that match, or the tags of the image already typed, and "Browse" opens the full catalog.
 */
export function ImagePicker({ id, value, invalid, onChange, onPick }: Props) {
  const listId = useId()
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(0)
  const [browsing, setBrowsing] = useState(false)

  const catalog = useImageCatalog()
  const images = catalog.data?.images
  // Until the catalog arrives (or if it can't), there is simply nothing to suggest: the field still takes any text.
  const { options, heading } = useMemo(() => (images ? suggestions(images, value) : { options: [], heading: null }), [images, value])
  const shown = open && options.length > 0

  function choose(o: Option) {
    setOpen(false)
    if (o.entry && o.tag) {
      // A tag picked for an image that is already in the field only changes the tag; nothing else is touched.
      if (images && findImage(images, splitRef(value).name)?.id === o.entry.id && splitRef(value).tag !== null) onChange(imageRef(o.entry, o.tag))
      else onPick(o.entry, o.tag)
    }
  }

  function onKeyDown(e: React.KeyboardEvent) {
    if (!shown) {
      if (e.key === 'ArrowDown' && options.length > 0) {
        setOpen(true)
        e.preventDefault()
      }
      return
    }
    if (e.key === 'ArrowDown') setActive((a) => (a + 1) % options.length)
    else if (e.key === 'ArrowUp') setActive((a) => (a - 1 + options.length) % options.length)
    else if (e.key === 'Enter') choose(options[Math.min(active, options.length - 1)])
    else if (e.key === 'Escape') setOpen(false)
    else return
    e.preventDefault()
  }

  return (
    <div className="relative">
      <div className="flex gap-2">
        <Input
          id={id}
          role="combobox"
          aria-expanded={shown}
          aria-controls={listId}
          aria-autocomplete="list"
          aria-activedescendant={shown ? `${listId}-${Math.min(active, options.length - 1)}` : undefined}
          autoComplete="off"
          spellCheck={false}
          value={value}
          placeholder="ghcr.io/acme/api:2.1"
          className="font-mono"
          aria-invalid={invalid}
          onChange={(e) => {
            onChange(e.target.value)
            setActive(0)
            setOpen(true)
          }}
          onFocus={() => setOpen(true)}
          onBlur={() => setOpen(false)}
          onKeyDown={onKeyDown}
        />
        <Button type="button" variant="outline" size="icon" onClick={() => setBrowsing(true)} aria-label="Browse images" title="Browse images">
          <LayoutGrid />
        </Button>
      </div>

      {shown && (
        // mousedown is cancelled so picking an option doesn't blur the input (which would close the list first)
        <div className="border-border bg-popover absolute z-30 mt-1 w-full overflow-hidden rounded-lg border shadow-[var(--shadow-pop)]" onMouseDown={(e) => e.preventDefault()}>
          {heading && <div className="text-muted-foreground px-3 pt-2 pb-1 text-[11px] font-medium tracking-wide uppercase">{heading}</div>}
          <ul id={listId} role="listbox" aria-label="Suggested images" className="max-h-72 overflow-y-auto p-1">
            {options.map((o, i) => (
              <li
                key={o.key}
                id={`${listId}-${i}`}
                role="option"
                aria-selected={i === active}
                onMouseEnter={() => setActive(i)}
                onClick={() => choose(o)}
                className={cn('flex cursor-pointer items-baseline justify-between gap-3 rounded-md px-2 py-1.5 text-sm', i === active && 'bg-accent')}
              >
                <span className="truncate">
                  <span className={cn(!o.entry && 'font-mono text-xs')}>{o.label}</span>
                  {o.sub && <span className="text-muted-foreground ml-2 font-mono text-xs">{o.sub}</span>}
                </span>
                {o.category && <span className="text-muted-foreground shrink-0 text-[11px]">{o.category}</span>}
              </li>
            ))}
          </ul>
          <button
            type="button"
            className="border-border text-muted-foreground hover:text-foreground hover:bg-muted w-full border-t px-3 py-2 text-left text-xs transition-colors"
            onClick={() => {
              setOpen(false)
              setBrowsing(true)
            }}
          >
            Browse all images…
          </button>
        </div>
      )}

      <ImageBrowser open={browsing} onOpenChange={setBrowsing} onSelect={onPick} />
    </div>
  )
}

/** What to suggest for the text in the field. */
function suggestions(images: CatalogImage[], value: string): { options: Option[]; heading: string | null } {
  const text = value.trim()
  if (text === '') {
    return {
      heading: 'Popular',
      options: popularImages(images).map((e) => ({ key: e.id, entry: e, tag: e.defaultTag, label: e.name, sub: e.image, category: e.category })),
    }
  }
  const { name, tag } = splitRef(text)
  const entry = findImage(images, name)

  // `postgres:` or `postgres:1`: the image is known, so suggest its tags.
  if (entry && tag !== null) {
    const tags = entry.tags.filter((t) => t.startsWith(tag) && t !== tag)
    return { heading: `${entry.name} tags`, options: tags.map((t) => ({ key: t, entry, tag: t, label: `${entry.image}:${t}` })) }
  }
  // A registry host or a tag on something unknown is a hand-typed reference: leave it alone.
  if (tag !== null || name.includes('.')) return { heading: null, options: [] }

  const found = searchImages(images, name, null, MAX)
  return {
    heading: found.length > 0 ? 'Catalog' : null,
    options: found.map((e) => ({ key: e.id, entry: e, tag: e.defaultTag, label: e.name, sub: e.image, category: e.category })),
  }
}
