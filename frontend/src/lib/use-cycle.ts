import { useEffect, useRef, useState } from 'react'
import { prefersReducedMotion } from '@/lib/use-typewriter'

// Counts 0..max (then rests one extra beat) and loops, but only while the
// returned ref's element is on screen. Reduced motion pins it at `max`.
export function useCycle<T extends HTMLElement>(max: number, ms: number) {
  const ref = useRef<T>(null)
  const [tick, setTick] = useState(prefersReducedMotion() ? max : 0)
  const [visible, setVisible] = useState(false)

  useEffect(() => {
    const el = ref.current
    if (!el) return
    const io = new IntersectionObserver(([e]) => setVisible(e.isIntersecting), { threshold: 0.3 })
    io.observe(el)
    return () => io.disconnect()
  }, [])

  useEffect(() => {
    if (!visible || prefersReducedMotion()) return
    const id = window.setInterval(() => setTick((t) => (t >= max + 2 ? 0 : t + 1)), ms)
    return () => clearInterval(id)
  }, [visible, max, ms])

  return { ref, tick: Math.min(tick, max) , restarting: tick > max }
}
