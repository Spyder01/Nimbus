import { Cloud } from 'lucide-react'
import { Link, useLocation } from 'react-router'
import { useSession } from '@/components/session-provider'
import { homeFor } from '@/lib/auth'

function Mark({ iconOnly }: { iconOnly: boolean }) {
  return (
    <>
      <span className="bg-brand-gradient grid size-8 place-items-center rounded-[10px] text-white shadow-sm">
        <Cloud className="size-[18px]" fill="currentColor" strokeWidth={1.5} />
      </span>
      <span className={iconOnly ? 'sr-only' : 'text-lg font-semibold tracking-tight'}>Nimbus</span>
    </>
  )
}

// Links home: the landing page when signed out, otherwise wherever a signed-in user belongs
// (profile until it's filled in, then the dashboard). Pass `link={false}` for the loading splash.
export function Logo({ iconOnly = false, link = true }: { iconOnly?: boolean; link?: boolean }) {
  const { me } = useSession()
  const { pathname } = useLocation()
  const to = homeFor(me)

  if (!link) return <div className="flex items-center gap-2.5"><Mark iconOnly={iconOnly} /></div>

  return (
    <Link
      to={to}
      aria-label="Nimbus home"
      className="focus-visible:ring-ring/50 flex items-center gap-2.5 rounded-lg outline-none focus-visible:ring-3"
      // Already there: a link to the same route does nothing, so bring the page back to the top instead.
      onClick={() => pathname === to && window.scrollTo({ top: 0, behavior: 'smooth' })}
    >
      <Mark iconOnly={iconOnly} />
    </Link>
  )
}
