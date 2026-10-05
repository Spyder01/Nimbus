import { Logo } from '@/components/logo'

export function Splash() {
  return (
    <div className="grid min-h-screen place-items-center" role="status" aria-label="Loading">
      <div className="live-dot">
        <Logo />
      </div>
    </div>
  )
}
