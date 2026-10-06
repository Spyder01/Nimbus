import type { FlowEdge, FlowNode } from './graph'

const PLAIN = /^[A-Za-z0-9_./:@-]+$/
const scalar = (v: string | number | boolean) =>
  typeof v === 'string' && !(PLAIN.test(v) && !/^(true|false|null|yes|no|~|[-+]?\d[\d_.]*)$/i.test(v))
    ? JSON.stringify(v)
    : String(v)

// A read-only YAML rendering of the canvas. Edges become `needs` (source needs target).
export function toYaml(appName: string, nodes: FlowNode[], edges: FlowEdge[]): string {
  const names = new Map(nodes.map((n) => [n.id, n.data.name.trim() || n.id]))
  const lines: string[] = [`app: ${scalar(appName)}`]
  if (nodes.length === 0) return `${lines[0]}\nservices: {}\n`
  lines.push('services:')

  for (const n of nodes) {
    const d = n.data
    lines.push(`  ${scalar(names.get(n.id)!)}:`)
    lines.push(`    image: ${d.image ? scalar(d.image) : '""'}`)
    if (d.port != null) lines.push(`    port: ${d.port}`)
    if (d.expose) lines.push('    expose: true')
    lines.push(`    kind: ${d.kind}`)
    lines.push(`    replicas: ${d.replicas}`)
    if (d.kind === 'stateless' && (d.minReplicas != null || d.maxReplicas != null || d.cpuTarget != null)) {
      const parts = [
        d.minReplicas != null && `min: ${d.minReplicas}`,
        d.maxReplicas != null && `max: ${d.maxReplicas}`,
        d.cpuTarget != null && `cpu: ${d.cpuTarget}`,
      ].filter(Boolean)
      lines.push(`    autoscale: { ${parts.join(', ')} }`)
    }
    if (d.volume) lines.push(`    volume: { size: ${scalar(d.volume.size)}, mount: ${scalar(d.volume.mountPath)} }`)
    const env = d.env.filter((e) => e.key)
    if (env.length) {
      lines.push('    env:')
      for (const e of env) lines.push(`      ${e.key}: ${e.secret ? '"<secret>"' : scalar(e.value ?? '')}`)
    }
    const needs = edges.filter((e) => e.source === n.id).map((e) => names.get(e.target))
    if (needs.length) lines.push(`    needs: [${needs.map((x) => scalar(x!)).join(', ')}]`)
  }
  return lines.join('\n') + '\n'
}
