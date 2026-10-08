// What the UI does with the curated image catalog (served by GET /api/images, see queries.ts): search, lookups, and
// turning a pick into container settings. The catalog is fetched once and searched here, so typing never waits on the
// network.
import type { ContainerData, EnvVar } from '@/features/apps/types'

export interface CatalogImage {
  id: string
  /** Full image name without a tag, e.g. `postgres` or `ghcr.io/open-webui/open-webui`. */
  image: string
  name: string
  category: string
  description: string
  port: number | null
  kind: 'stateless' | 'stateful'
  expose: boolean
  tags: string[]
  defaultTag: string
  /** Suggested container name. */
  base: string
  keywords: string[]
  volume?: { mountPath: string; size: string }
  env?: EnvVar[]
  popular?: boolean
}

export interface ImageCatalog {
  /** In display order. */
  categories: string[]
  images: CatalogImage[]
}

export const popularImages = (images: CatalogImage[]) => images.filter((i) => i.popular)

export const imageRef = (e: CatalogImage, tag: string) => `${e.image}:${tag}`

/** Splits `postgres:16` into its name and tag. A colon followed by a slash is a registry port, not a tag. */
export function splitRef(text: string): { name: string; tag: string | null } {
  const t = text.trim()
  const i = t.lastIndexOf(':')
  if (i === -1 || t.slice(i + 1).includes('/')) return { name: t, tag: null }
  return { name: t.slice(0, i), tag: t.slice(i + 1) }
}

/** The catalog entry for an image name, ignoring a `docker.io/` or `library/` prefix. */
export function findImage(images: CatalogImage[], name: string): CatalogImage | undefined {
  const n = name.trim().toLowerCase().replace(/^(docker\.io\/)?(library\/)?/, '')
  return images.find((i) => i.image === n)
}

const norm = (s: string) => s.toLowerCase()

function score(e: CatalogImage, tokens: string[]): number {
  const last = norm(e.image.split('/').pop() ?? e.image)
  const name = norm(e.name)
  const keywords = e.keywords.map(norm)
  const category = norm(e.category)
  const description = norm(e.description)
  let total = 0
  for (const t of tokens) {
    let s = 0
    if (last === t || name === t || norm(e.image) === t) s = 100
    else if (last.startsWith(t) || name.startsWith(t)) s = 60
    else if (last.includes(t) || name.includes(t) || norm(e.image).includes(t)) s = 40
    else if (keywords.includes(t)) s = 30
    else if (keywords.some((k) => k.includes(t))) s = 20
    else if (category.includes(t)) s = 10
    else if (description.includes(t)) s = 5
    if (s === 0) return 0 // every word has to match something
    total += s
  }
  return e.popular ? total + 15 : total // among equal matches, the common image comes first
}

/** Entries matching every word of the query, best first. An empty query lists everything (popular ones first). */
export function searchImages(images: CatalogImage[], query: string, category?: string | null, limit = Infinity): CatalogImage[] {
  const tokens = norm(query).split(/[\s/]+/).filter(Boolean)
  const pool = category ? images.filter((i) => i.category === category) : images
  const ranked =
    tokens.length === 0
      ? pool.map((e) => ({ e, s: e.popular ? 1 : 0 }))
      : pool.map((e) => ({ e, s: score(e, tokens) })).filter((x) => x.s > 0)
  ranked.sort((a, b) => b.s - a.s || a.e.name.localeCompare(b.e.name))
  return ranked.slice(0, limit).map((x) => x.e)
}

const copyEnv = (e: EnvVar): EnvVar => ({ ...e })

/** Everything the catalog knows about running this image, for a brand-new container. */
export function dataFromEntry(e: CatalogImage, tag: string): Partial<ContainerData> {
  return {
    image: imageRef(e, tag),
    port: e.port,
    expose: e.expose,
    kind: e.kind,
    env: (e.env ?? []).map(copyEnv),
    volume: e.volume ? { size: e.volume.size, mountPath: e.volume.mountPath } : null,
  }
}

/** A container template (same shape as the canvas's built-in ones) for adding a catalog image. */
export function templateFromEntry(e: CatalogImage, tag: string) {
  return {
    key: `catalog-${e.id}`,
    label: e.name,
    hint: e.description,
    base: e.base,
    data: dataFromEntry(e, tag),
  }
}

/**
 * What to change on an existing container when the user picks an image for it. Only fills in what is still empty
 * (name, port, missing environment variables) and never overwrites choices the user already made, apart from the
 * image itself. A stateless container with no autoscaling becomes stateful, with a volume, for a database image.
 */
export function applyEntry(current: ContainerData, e: CatalogImage, tag: string): Partial<ContainerData> {
  const patch: Partial<ContainerData> = { image: imageRef(e, tag) }
  if (!current.name.trim()) patch.name = e.base
  if (current.port == null && e.port != null) patch.port = e.port

  const untouchedAutoscale = current.minReplicas == null && current.maxReplicas == null && current.cpuTarget == null
  if (e.kind === 'stateful' && current.kind === 'stateless' && !current.volume && untouchedAutoscale) {
    patch.kind = 'stateful'
    patch.volume = e.volume ? { size: e.volume.size, mountPath: e.volume.mountPath } : { size: '10Gi', mountPath: '/data' }
  }

  const have = new Set(current.env.map((v) => v.key))
  const missing = (e.env ?? []).filter((v) => !have.has(v.key)).map(copyEnv)
  if (missing.length > 0) patch.env = [...current.env, ...missing]
  return patch
}
