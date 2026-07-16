import { ChevronRight, GitBranch, Search } from 'lucide-react'
import { useDeferredValue, useMemo, useState } from 'react'
import { formatClock, formatDuration, truncateMiddle } from '../lib/format'
import type { TraceSummary } from '../types'

interface RecentTracesProps {
  traces: TraceSummary[]
  onSelect: (trace: TraceSummary) => void
  expanded?: boolean
}

function routeTone(route?: string): string {
  const normalized = route?.toLowerCase() ?? ''
  if (normalized.includes('reason')) return 'reasoning'
  if (normalized.includes('fast')) return 'fast'
  return 'general'
}

export function RecentTraces({ traces, onSelect, expanded = false }: RecentTracesProps) {
  const [query, setQuery] = useState('')
  const deferredQuery = useDeferredValue(query.trim().toLowerCase())
  const visible = useMemo(() => {
    const matches = deferredQuery
      ? traces.filter((trace) => trace.id.toLowerCase().includes(deferredQuery) || trace.workload?.toLowerCase().includes(deferredQuery))
      : traces
    return expanded ? matches : matches.slice(0, 8)
  }, [deferredQuery, expanded, traces])

  return (
    <section className={`panel recent-traces ${expanded ? 'recent-traces--expanded' : ''}`} aria-labelledby="traces-title">
      <div className="panel-heading traces-heading">
        <div>
          <h2 id="traces-title">{expanded ? 'Traces' : 'Recent traces'}</h2>
          <p>{traces.length ? `${traces.length} traces in this session` : 'End-to-end request timing'}</p>
        </div>
        <label className="trace-search">
          <Search size={16} />
          <input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Filter by trace ID" />
        </label>
      </div>
      <div className="trace-table" role="table" aria-label="Recent request traces">
        <div className="trace-table__head" role="row">
          <span>Trace ID</span><span>Started</span><span>Duration</span><span>Selected route</span><span>Model family</span><span>Services</span><span>Timeline</span><span />
        </div>
        {visible.map((trace) => (
          <button className="trace-row" role="row" key={trace.id} onClick={() => onSelect(trace)}>
            <span className="trace-id"><i className={`trace-status trace-status--${trace.status}`} /><code>{truncateMiddle(trace.id, 22)}</code></span>
            <time>{formatClock(trace.startedAt)}</time>
            <span>{formatDuration(trace.durationMs)}</span>
            <span className={`trace-route trace-route--${routeTone(trace.metadata?.selectedRoute)}`}>{trace.metadata?.selectedRoute || '—'}</span>
            <code className="trace-model">{trace.metadata?.selectedModelFamily || '—'}</code>
            <span className="service-tags">{trace.services.slice(0, 3).map((service) => <i key={service}>{service}</i>)}</span>
            <span className="mini-waterfall" aria-hidden="true">
              {(trace.spans.length ? trace.spans : [{ id: 'empty', durationMs: trace.durationMs, startedAtMs: 0, status: trace.status, name: '', service: '' }]).slice(0, 4).map((span) => (
                <i key={span.id} className={`mini-waterfall__bar mini-waterfall__bar--${span.status}`} style={{ width: `${Math.max(8, Math.min(100, (span.durationMs / Math.max(trace.durationMs, 1)) * 100))}%`, marginLeft: `${Math.min(72, (span.startedAtMs / Math.max(trace.durationMs, 1)) * 100)}%` }} />
              ))}
            </span>
            <ChevronRight size={17} />
          </button>
        ))}
      </div>
      {!visible.length ? (
        <div className="list-empty list-empty--traces">
          <GitBranch size={22} />
          <strong>{traces.length ? 'No trace matches that ID' : 'No traces captured yet'}</strong>
          <span>Start a workload to inspect request latency and metadata.</span>
        </div>
      ) : null}
    </section>
  )
}
