import { ArrowRight } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { GitHubIcon, GoogleIcon } from '@/components/brand-icons'
import { ENABLED_PROVIDERS, startOAuth } from '@/lib/auth'

export function AuthButtons() {
  const googleEnabled = ENABLED_PROVIDERS.includes('google')
  return (
    <div className="flex flex-col gap-3">
      <Button size="lg" className="sheen h-11 text-sm" onClick={() => startOAuth('github')}>
        <GitHubIcon className="size-[18px]" />
        Continue with GitHub
        <ArrowRight className="ml-auto opacity-60" />
      </Button>
      <Button
        variant="outline"
        size="lg"
        className="h-11 text-sm"
        disabled={!googleEnabled}
        onClick={() => startOAuth('google')}
      >
        <GoogleIcon className="size-[18px]" />
        Continue with Google
        {googleEnabled ? (
          <ArrowRight className="ml-auto opacity-60" />
        ) : (
          <span className="bg-muted text-muted-foreground ml-auto rounded px-1.5 py-0.5 text-[10px] font-medium">
            Soon
          </span>
        )}
      </Button>
    </div>
  )
}
