import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError } from '@/lib/api'
import { saveDraft } from '@/features/apps/queries'
import type { Draft, Problem } from '@/features/apps/types'
import { draftJson, graphJson, toGraph, type FlowEdge, type FlowNode } from './graph'

export type SaveStatus = 'saved' | 'dirty' | 'saving' | 'error' | 'conflict'

const DEBOUNCE_MS = 1000

// Saves the whole graph (PUT /draft) shortly after the user stops editing.
//  - one request at a time; edits made while it is in flight trigger another save right after
//  - baseRevision gives optimistic concurrency: a 409 means the draft changed elsewhere (status "conflict")
//  - flush() lets other actions (save version, restore) wait for pending edits to reach the server
export function useAutosave(appId: string, initial: Draft, nodes: FlowNode[], edges: FlowEdge[]) {
  const [status, setStatusState] = useState<SaveStatus>('saved')
  const [problems, setProblems] = useState<Problem[]>(initial.problems)
  const [error, setError] = useState<string | null>(null)

  const statusRef = useRef<SaveStatus>('saved')
  const revision = useRef(initial.revision)
  const savedJson = useRef(draftJson(initial))
  const latest = useRef({ nodes, edges })
  const inflight = useRef<Promise<void> | null>(null)
  const timer = useRef<number | undefined>(undefined)

  const setStatus = useCallback((s: SaveStatus) => {
    statusRef.current = s
    setStatusState(s)
  }, [])

  const run = useCallback((): Promise<void> => {
    if (inflight.current) return inflight.current
    const p = (async () => {
      for (;;) {
        const { nodes, edges } = latest.current
        const json = graphJson(nodes, edges)
        if (json === savedJson.current) return setStatus('saved')
        setStatus('saving')
        try {
          const res = await saveDraft(appId, { ...toGraph(nodes, edges), baseRevision: revision.current })
          revision.current = res.revision
          savedJson.current = json
          setProblems(res.problems)
          setError(null)
        } catch (e) {
          if (e instanceof ApiError && e.status === 409) return setStatus('conflict')
          setError(e instanceof Error ? e.message : 'Could not save')
          return setStatus('error')
        }
      }
    })().finally(() => {
      inflight.current = null
    })
    inflight.current = p
    return p
  }, [appId, setStatus])

  useEffect(() => {
    latest.current = { nodes, edges }
    if (statusRef.current === 'conflict') return
    if (graphJson(nodes, edges) === savedJson.current && !inflight.current) {
      if (statusRef.current !== 'saved') setStatus('saved')
      return
    }
    if (statusRef.current !== 'saving') setStatus('dirty')
    window.clearTimeout(timer.current)
    timer.current = window.setTimeout(() => void run(), DEBOUNCE_MS)
    return () => window.clearTimeout(timer.current)
  }, [nodes, edges, run, setStatus])

  // Leaving the canvas with unsaved edits: save them now rather than losing the debounce.
  useEffect(
    () => () => {
      if (statusRef.current === 'dirty') void run()
    },
    [run],
  )

  // Warn before closing the tab while edits haven't reached the server.
  useEffect(() => {
    const warn = (e: BeforeUnloadEvent) => {
      if (['dirty', 'saving', 'error'].includes(statusRef.current)) e.preventDefault()
    }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [])

  /** Saves any pending edits now. Resolves true when the server is up to date. */
  const flush = useCallback(async () => {
    window.clearTimeout(timer.current)
    await run()
    return statusRef.current === 'saved'
  }, [run])

  /** Adopt a draft from the server (after restore or a conflict reload). */
  const reset = useCallback(
    (draft: Draft) => {
      window.clearTimeout(timer.current)
      revision.current = draft.revision
      savedJson.current = draftJson(draft)
      setProblems(draft.problems)
      setError(null)
      setStatus('saved')
    },
    [setStatus],
  )

  return { status, problems, error, flush, reset, retry: run, getRevision: () => revision.current }
}
