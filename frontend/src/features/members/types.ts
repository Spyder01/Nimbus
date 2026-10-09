// Mirrors the backend's member DTOs (services/backend .../users/dto/MemberDtos.kt).
import type { Role } from '@/lib/auth'

export interface Member {
  id: string
  name: string | null
  email: string | null
  avatarUrl: string | null
  role: Role
  /** How they sign in: "GITHUB" or "GOOGLE". Null if there is no sign-in record. */
  provider: string | null
  /** Their handle with that provider, e.g. their GitHub username. */
  login: string | null
  profileUpdated: boolean
  createdAt: string | null
}

export interface MemberPage {
  items: Member[]
  /** How many members match, across all pages. */
  total: number
  /** Zero-based. */
  page: number
  size: number
}
