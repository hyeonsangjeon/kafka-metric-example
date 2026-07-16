import { ChevronDown, ChevronUp, GitCommitHorizontal, Radio, Waves } from 'lucide-react'
import { useState } from 'react'
import { formatCompact } from '../lib/format'
import type { KpiValue, PartitionTelemetry, RunState } from '../types'

interface TelemetryRiverProps {
  partitions: PartitionTelemetry[]
  kpis: KpiValue[]
  run: RunState | null
  connected: boolean
  providerMode: 'simulated' | 'ollama' | 'foundry' | 'unknown'
  transportMode: 'memory' | 'kafka' | 'unknown'
}

function kpiValue(kpis: KpiValue[], key: string): number | null {
  return kpis.find((item) => item.key === key)?.value ?? null
}

function EmptyRiver({ connected }: { connected: boolean }) {
  return (
    <div className="river-empty">
      <div className="river-empty__lanes" aria-hidden="true">
        {[0, 1, 2].map((lane) => (
          <div className="river-empty__lane" key={lane}>
            {Array.from({ length: 26 }, (_, index) => <span key={index} style={{ opacity: 0.2 + ((index + lane) % 5) * 0.08 }} />)}
          </div>
        ))}
      </div>
      <div className="river-empty__copy">
        <Radio size={20} />
        <strong>{connected ? 'Waiting for the first telemetry event' : 'Telemetry stream is disconnected'}</strong>
        <span>{connected ? 'Start a workload to populate Kafka partition lanes.' : 'The lab will resume automatically when the API is available.'}</span>
      </div>
    </div>
  )
}

export function TelemetryRiver({
  partitions,
  kpis,
  run,
  connected,
  providerMode,
  transportMode,
}: TelemetryRiverProps) {
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set())
  const throughput = kpiValue(kpis, 'throughput')
  const latency = kpiValue(kpis, 'p95-latency')
  const providerLabel = providerMode === 'simulated' ? 'Simulator' : providerMode === 'ollama' ? 'Ollama' : providerMode === 'foundry' ? 'Foundry' : 'AI provider'
  const transportLabel = transportMode === 'memory' ? 'memory projection' : transportMode === 'kafka' ? 'Kafka' : 'event transport'

  const toggle = (id: string) => {
    setExpanded((current) => {
      const next = new Set(current)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  return (
    <section className="panel telemetry-river" aria-labelledby="river-title">
      <div className="panel-heading telemetry-river__heading">
        <div>
          <h2 id="river-title"><Waves size={18} /> Telemetry River</h2>
          <p>Live delivery pressure across the request path and Kafka partitions.</p>
        </div>
        <div className="river-legend" aria-label="Telemetry legend">
          <span><i className="legend-dot legend-dot--active" /> active</span>
          <span><i className="legend-dot legend-dot--lag" /> lagging</span>
          <span><i className="legend-dot legend-dot--error" /> error</span>
        </div>
      </div>

      {partitions.length ? (
        <div className="river-canvas">
          <div className="river-now" aria-hidden="true"><span>now</span></div>
          <div className="river-summary">
            <span className="lane-icon"><GitCommitHorizontal size={20} /></span>
            <div className="lane-name"><strong>Telemetry path</strong><span>{providerLabel} → {transportLabel} → browser{run?.workloadLabel || run?.workloadId ? ` · ${run.workloadLabel || run.workloadId}` : ''}</span></div>
            <div className="lane-stat"><span>Rate</span><strong>{formatCompact(throughput)} <small>ev/s</small></strong></div>
            <div className="lane-stat"><span>P95</span><strong>{formatCompact(latency)} <small>ms</small></strong></div>
          </div>
          <div className="partition-list">
            {partitions.map((partition) => {
              const isExpanded = expanded.has(partition.id)
              return (
                <div className={`partition-lane ${isExpanded ? 'partition-lane--expanded' : ''}`} key={partition.id}>
                  <button className="partition-lane__header" onClick={() => toggle(partition.id)} aria-expanded={isExpanded}>
                    <span className="lane-icon"><Waves size={19} /></span>
                    <span className="lane-name"><strong>Kafka / {partition.topic}</strong><span>partition {partition.partition ?? '—'}</span></span>
                    <span className={`lane-stat ${partition.lag != null && partition.lag > 0 ? 'lane-stat--warn' : ''}`}><span>Lag</span><strong>{formatCompact(partition.lag)}</strong></span>
                    <span className="lane-stat lane-stat--offset"><span>End offset</span><strong>{formatCompact(partition.endOffset, 0)}</strong></span>
                    {isExpanded ? <ChevronUp size={18} /> : <ChevronDown size={18} />}
                  </button>
                  <div className="partition-lane__blocks" aria-label={`Partition ${partition.partition} activity timeline`}>
                    {partition.blocks.map((block, index) => <span className={`block block--${block}`} key={`${partition.id}-${index}`} />)}
                  </div>
                  {isExpanded ? (
                    <div className="partition-lane__detail">
                      <span>Produced <strong>{formatCompact(partition.produced)}</strong></span>
                      <span>Consumed <strong>{formatCompact(partition.consumed)}</strong></span>
                      <span>Peak lag <strong>{formatCompact(partition.peakLag)}</strong></span>
                      <span>Duplicates <strong>{formatCompact(partition.duplicatesDropped)}</strong></span>
                    </div>
                  ) : null}
                </div>
              )
            })}
          </div>
          <div className="river-axis" aria-hidden="true"><span>−30s</span><strong>−15s</strong><span>now</span></div>
        </div>
      ) : <EmptyRiver connected={connected} />}
    </section>
  )
}
