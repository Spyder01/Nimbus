import { Check, ChevronDown, Cpu, Database, Globe, Lock, Plus, Timer, Zap } from 'lucide-react'
import { Reveal } from '@/components/reveal'
import { useCycle } from '@/lib/use-cycle'
import { cn } from '@/lib/utils'

const fade = 'mask-[linear-gradient(to_bottom,#000_62%,transparent)]'

/* 1 — pick the pieces of your stack */
const SERVICES = [
  { icon: Globe, name: 'Web service' },
  { icon: Lock, name: 'Private service' },
  { icon: Cpu, name: 'Background worker' },
  { icon: Timer, name: 'Cron job' },
  { icon: Database, name: 'Postgres' },
  { icon: Zap, name: 'Redis' },
]

function PickerMock() {
  const { ref, tick } = useCycle<HTMLDivElement>(SERVICES.length, 750)
  return (
    <div ref={ref} className={cn('w-full', fade)}>
      <div className="bg-muted/70 text-muted-foreground flex items-center gap-3 rounded-t-lg px-4 py-3 text-sm">
        <Plus className="size-4" /> New service
      </div>
      <ul className="border-border bg-card/60 rounded-b-lg border border-t-0">
        {SERVICES.map(({ icon: Icon, name }, i) => {
          const active = i === tick
          const done = i < tick
          return (
            <li
              key={name}
              className={cn(
                'flex h-11 items-center gap-3 px-4 text-sm transition-all duration-300',
                active ? 'bg-brand-gradient text-white' : done ? '' : 'text-muted-foreground',
              )}
            >
              <Icon className="size-4" />
              {name}
              <Check
                className={cn(
                  'text-ok ml-auto size-4 transition-all duration-300',
                  done ? 'scale-100 opacity-100' : 'scale-50 opacity-0',
                )}
              />
            </li>
          )
        })}
      </ul>
    </div>
  )
}

/* 2 — point at an image, hit deploy */
const LOGS = ['Pulling ghcr.io/acme/api:2.1', 'Scheduling 3 pods', 'Health checks passing', 'Your app is live']

function DeployMock() {
  const { ref, tick } = useCycle<HTMLDivElement>(LOGS.length + 1, 700)
  const fields = [
    ['Image', 'ghcr.io/acme/api:2.1'],
    ['Port', '8080'],
    ['Replicas', '3'],
  ]
  const deploying = tick >= 1
  const live = tick > LOGS.length
  return (
    <div ref={ref} className={cn('w-full', fade)}>
      <div className="border-border bg-card/60 space-y-3 rounded-lg border p-4">
        {fields.map(([label, value]) => (
          <div key={label} className="grid grid-cols-[5rem_1fr] items-center gap-3 text-sm">
            <span className="text-muted-foreground text-right text-xs">{label}</span>
            <span className="border-border bg-background/60 truncate rounded-md border px-3 py-2 font-mono text-xs">
              {value}
            </span>
          </div>
        ))}
        <div className="grid grid-cols-[5rem_1fr] items-center gap-3 text-sm">
          <span className="text-muted-foreground text-right text-xs">Autoscale</span>
          <span className="border-border bg-background/60 flex items-center justify-between rounded-md border px-3 py-2 text-xs">
            CPU above 70% <ChevronDown className="size-3.5 opacity-60" />
          </span>
        </div>
      </div>
      <div className="mt-4 flex items-start gap-3">
        <span
          className={cn(
            'rounded-md px-4 py-2.5 text-sm font-medium transition-all duration-300',
            live ? 'bg-ok/15 text-ok' : 'bg-primary text-primary-foreground',
            tick === 1 && 'scale-95',
          )}
        >
          {live ? 'Live' : deploying ? 'Deploying…' : 'Deploy'}
        </span>
        <div className="text-muted-foreground min-h-20 flex-1 space-y-1.5 font-mono text-[11px] leading-4">
          {LOGS.slice(0, Math.max(0, tick - 1)).map((l, i) => (
            <div key={l} className="rise" style={{ animationDuration: '.3s' }}>
              <span className={i === LOGS.length - 1 ? 'text-ok' : ''}>==&gt;</span> {l}
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}

/* 3 — Nimbus keeps it running */
const EVENTS = [
  ['Rolled out api:2.1', 'all 3 pods ready'],
  ['Scaled api 3 → 5 pods', 'cpu 84% for 60s'],
  ['Postgres backup complete', '10Gi · 4s'],
  ['Scaled api 5 → 3 pods', 'cpu 31% for 5m'],
]

function EventsMock() {
  const { ref, tick } = useCycle<HTMLDivElement>(EVENTS.length, 1100)
  const shown = EVENTS.slice(0, tick).reverse()
  return (
    <div ref={ref} className={cn('w-full', fade)}>
      <div className="text-muted-foreground mb-3 flex items-center gap-2 font-mono text-xs">
        <span className="bg-ok live-dot size-1.5 rounded-full" /> acme-shop · healthy
      </div>
      <div className="border-border bg-card/60 divide-border min-h-64 divide-y rounded-lg border">
        {shown.map(([title, meta]) => (
          <div key={title} className="rise flex items-center gap-3 px-4 py-3.5" style={{ animationDuration: '.4s' }}>
            <span className="bg-ok/15 text-ok grid size-5 shrink-0 place-items-center rounded-full">
              <Check className="size-3" strokeWidth={3} />
            </span>
            <div className="min-w-0">
              <div className="truncate text-sm">{title}</div>
              <div className="text-muted-foreground font-mono text-[11px]">{meta}</div>
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}

const STEPS = [
  {
    title: 'Pick your pieces',
    body: 'Web services, workers, cron jobs, databases and caches. Add what your app needs, or paste a YAML.',
    mock: <PickerMock />,
  },
  {
    title: 'Point to an image',
    body: 'Give us a container image, a port and a replica count. We wire up networking, volumes and secrets.',
    mock: <DeployMock />,
  },
  {
    title: 'Nimbus does the rest',
    body: 'Rollouts, autoscaling, backups and health checks run on Kubernetes while you ship.',
    mock: <EventsMock />,
  },
]

export function Steps() {
  return (
    <section>
      <Reveal>
        <h2 className="text-4xl font-light tracking-tight sm:text-6xl">Click, click, done.</h2>
      </Reveal>
      <div className="mt-12 grid gap-12 lg:mt-16 lg:grid-cols-3 lg:gap-10">
        {STEPS.map((s, i) => (
          <Reveal key={s.title} delay={i * 120} className="flex flex-col">
            <span className="bg-brand-gradient mb-6 grid size-9 place-items-center rounded-md text-sm font-medium text-white">
              {i + 1}
            </span>
            <h3 className="text-2xl font-light tracking-tight">{s.title}</h3>
            <p className="text-muted-foreground mt-3 min-h-[4.5rem] text-pretty">{s.body}</p>
            <div className="mt-8 flex h-[28rem] items-start overflow-hidden">{s.mock}</div>
          </Reveal>
        ))}
      </div>
    </section>
  )
}
