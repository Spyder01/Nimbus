const IMAGES = [
  'nginx:1.27', 'postgres:16', 'redis:7.2', 'node:22', 'python:3.12', 'golang:1.23',
  'rabbitmq:3', 'mongo:7', 'minio/minio', 'grafana/grafana', 'traefik:v3', 'mysql:8',
]

export function Marquee() {
  return (
    <div className="marquee-mask overflow-hidden" aria-label="Works with any container image">
      <div className="marquee-track flex w-max gap-3">
        {[...IMAGES, ...IMAGES].map((img, i) => (
          <span
            key={i}
            aria-hidden={i >= IMAGES.length}
            className="border-border bg-surface text-muted-foreground rounded-full border px-3.5 py-1.5 font-mono text-xs backdrop-blur"
          >
            {img}
          </span>
        ))}
      </div>
    </div>
  )
}
