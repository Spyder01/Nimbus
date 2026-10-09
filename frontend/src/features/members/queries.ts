import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { request } from '@/lib/api'
import type { Role } from '@/lib/auth'
import type { Member, MemberPage } from './types'

const membersKey = ['members'] as const

export const PAGE_SIZE = 25

/** One page of members, optionally filtered. The previous page stays on screen while the next one loads. */
export function useMembers(q: string, page: number) {
  return useQuery({
    queryKey: [...membersKey, q, page],
    queryFn: () => {
      const params = new URLSearchParams({ page: String(page), size: String(PAGE_SIZE) })
      if (q) params.set('q', q)
      return request<MemberPage>(`/api/admin/members?${params}`)
    },
    placeholderData: keepPreviousData,
  })
}

/** Super admins only. It counts straight away on the server, whatever the member is doing. */
export function useSetMemberRole() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (v: { id: string; role: Exclude<Role, 'SUPER_ADMIN'> }) =>
      request<Member>(`/api/admin/members/${encodeURIComponent(v.id)}/role`, { method: 'PUT', json: { role: v.role } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: membersKey }),
  })
}
