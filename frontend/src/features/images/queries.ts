import { useQuery } from '@tanstack/react-query'
import { request } from '@/lib/api'
import type { ImageCatalog } from './catalog'

/**
 * The curated image catalog. It only changes with a release, so it is fetched once per visit and never refetched on
 * focus. While it loads, or if it can't, the image field still works as a plain text box.
 */
export function useImageCatalog() {
  return useQuery({
    queryKey: ['images'],
    queryFn: () => request<ImageCatalog>('/api/images'),
    staleTime: Infinity,
    refetchOnWindowFocus: false,
    retry: 1,
  })
}
