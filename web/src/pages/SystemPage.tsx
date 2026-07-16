import { Bot, Check, ChevronRight, Cloud, Database, LockKeyhole, MonitorUp, RadioTower, ServerCog, ShieldCheck } from 'lucide-react'
import type { ComponentType } from 'react'
import { StatusDot } from '../components/StatusDot'
import { formatCompact, formatRelative } from '../lib/format'
import type { ConnectionState, HealthState, LabConfig, LabSnapshot, SystemNode } from '../types'

interface SystemPageProps {
  snapshot: LabSnapshot
  connection: ConnectionState
  config: LabConfig
}

const NODE_ICONS: Record<SystemNode['kind'], ComponentType<{ size?: number }>> = {
  foundry: Bot,
  gateway: Cloud,
  kafka: Database,
  consumer: RadioTower,
  browser: MonitorUp,
}

function fallbackNodes(snapshot: LabSnapshot, connection: ConnectionState, config: LabConfig): SystemNode[] {
  const health: HealthState = connection === 'connected' ? 'healthy' : connection === 'connecting' ? 'degraded' : 'offline'
  const providerName = config.mode === 'simulated' ? 'Deterministic simulator' : 'Microsoft Foundry'
  const providerDetail = config.mode === 'simulated' ? 'Credential-free local provider' : 'Responses API · store(false)'
  const transportName = config.transport === 'memory' ? 'Memory transport' : 'Kafka'
  return [
    { id: 'foundry', name: providerName, detail: providerDetail, kind: 'foundry', health: snapshot.foundryHealth === 'unknown' ? health : snapshot.foundryHealth },
    { id: 'emitter', name: 'Telemetry emitter', detail: 'Redacted metadata envelope', kind: 'gateway', health },
    { id: 'kafka', name: transportName, detail: 'Partitioned telemetry transport', kind: 'kafka', health: snapshot.kafkaHealth === 'unknown' ? health : snapshot.kafkaHealth, metric: snapshot.partitions.length ? `${snapshot.partitions.length} partitions` : undefined },
    { id: 'consumer', name: 'Stream consumer', detail: 'SSE projection', kind: 'consumer', health, metric: snapshot.streamRate != null ? `${formatCompact(snapshot.streamRate)} ev/s` : undefined },
    { id: 'browser', name: 'Live Lab', detail: 'Ephemeral browser session', kind: 'browser', health },
  ]
}

export function SystemPage({ snapshot, connection, config }: SystemPageProps) {
  const nodes = snapshot.system.length ? snapshot.system : fallbackNodes(snapshot, connection, config)
  return (
    <div className="page page-enter system-page">
      <header className="page-header">
        <div className="page-header__icon"><ServerCog size={23} /></div>
        <div><h1>System map</h1><p>See the complete telemetry path, health boundary, and privacy contract.</p></div>
      </header>

      <section className="panel system-map" aria-labelledby="system-map-title">
        <div className="panel-heading"><div><h2 id="system-map-title">Delivery path</h2><p>Each hop emits or transports metadata only.</p></div><span className="system-updated">Updated {formatRelative(snapshot.capturedAt)}</span></div>
        <div className="system-flow">
          {nodes.map((node, index) => {
            const Icon = NODE_ICONS[node.kind]
            return (
              <div className="system-flow__step" key={node.id}>
                <article className={`system-node system-node--${node.health}`}>
                  <span className="system-node__icon"><Icon size={24} /></span>
                  <span className="system-node__copy"><strong>{node.name}</strong><small>{node.detail}</small></span>
                  <span className="system-node__status"><StatusDot status={node.health} />{node.metric || node.health}</span>
                </article>
                {index < nodes.length - 1 ? <span className="system-connector" aria-hidden="true"><i /><ChevronRight size={17} /></span> : null}
              </div>
            )
          })}
        </div>
      </section>

      <div className="system-details">
        <section className="panel privacy-contract">
          <div className="privacy-contract__icon"><ShieldCheck size={25} /></div>
          <div><h2>Privacy contract</h2><p>Observability is useful only when it does not create a second copy of sensitive AI payloads.</p></div>
          <ul>
            <li><Check size={16} /> Prompt and response bodies are never persisted</li>
            <li><Check size={16} /> Kafka receives hashes, sizes, timing, and delivery metadata only</li>
            <li><Check size={16} /> Endpoint, deployment, and tenant identifiers stay out of telemetry</li>
            <li><Check size={16} /> Session data can be deleted from the header at any time</li>
          </ul>
          <div className="privacy-contract__seal"><LockKeyhole size={18} /><span>Telemetry-only by design</span></div>
        </section>
        <section className="panel system-session">
          <div className="panel-heading"><div><h2>Current session</h2><p>Ephemeral lab state</p></div></div>
          <dl>
            <div><dt>Connection</dt><dd><StatusDot status={connection} /> {connection}</dd></div>
            <div><dt>Session ID</dt><dd><code>{snapshot.sessionId || 'Not established'}</code></dd></div>
            <div><dt>Active run</dt><dd>{snapshot.run?.id || 'None'}</dd></div>
            <div><dt>Captured traces</dt><dd>{snapshot.traces.length}</dd></div>
            <div><dt>Stream events</dt><dd>{snapshot.events.length}</dd></div>
          </dl>
        </section>
      </div>
    </div>
  )
}
