import { useEffect, useState } from 'react'

export const prefersReducedMotion = () =>
  typeof matchMedia !== 'undefined' && matchMedia('(prefers-reduced-motion: reduce)').matches

// Types each phrase, pauses, deletes it, then moves to the next.
export function useTypewriter(phrases: string[], { type = 70, erase = 35, hold = 1600 } = {}) {
  const [text, setText] = useState(() => (prefersReducedMotion() ? phrases[0] : ''))

  useEffect(() => {
    if (prefersReducedMotion()) return
    let i = 0
    let n = 0
    let deleting = false
    let timer: number

    const tick = () => {
      const word = phrases[i]
      n += deleting ? -1 : 1
      setText(word.slice(0, n))
      let delay = deleting ? erase : type
      if (!deleting && n === word.length) {
        deleting = true
        delay = hold
      } else if (deleting && n === 0) {
        deleting = false
        i = (i + 1) % phrases.length
        delay = 350
      }
      timer = window.setTimeout(tick, delay)
    }
    timer = window.setTimeout(tick, 500)
    return () => clearTimeout(timer)
  }, [phrases, type, erase, hold])

  return text
}

// Reveals `total` characters progressively; restarts whenever `restartKey` changes.
export function useTypedCount(total: number, restartKey: unknown, perTick = 3, ms = 16) {
  const [count, setCount] = useState(0)

  useEffect(() => {
    if (prefersReducedMotion()) {
      setCount(total)
      return
    }
    setCount(0)
    const id = window.setInterval(() => {
      setCount((c) => {
        if (c >= total) {
          clearInterval(id)
          return c
        }
        return c + perTick
      })
    }, ms)
    return () => clearInterval(id)
    // total intentionally excluded: a changing replica count must not retype the file
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [restartKey])

  return Math.min(count, total)
}
