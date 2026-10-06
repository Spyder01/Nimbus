import { createContext } from 'react'
import type { Problem } from '@/features/apps/types'

// nodeId -> completeness problems reported by the backend on the last save.
export const ProblemsContext = createContext<Map<string, Problem[]>>(new Map())
