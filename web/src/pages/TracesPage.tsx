import { GitBranch } from 'lucide-react'
import type { TraceSummary } from '../types'
import { RecentTraces } from '../features/RecentTraces'

export function TracesPage({ traces, onSelect }: { traces: TraceSummary[]; onSelect: (trace: TraceSummary) => void }) {
  const successful = traces.filter((trace) => trace.status === 'ok').length
  const failed = traces.filter((trace) => trace.status === 'error').length
  const average = traces.length ? traces.reduce((sum, trace) => sum + trace.durationMs, 0) / traces.length : 0
  return (
    <div className="page page-enter traces-page">
      <header className="page-header">
        <div className="page-header__icon"><GitBranch size={23} /></div>
        <div><h1>Request traces</h1><p>Inspect Foundry latency and Kafka delivery metadata without exposing payload bodies.</p></div>
      </header>
      <section className="trace-summary" aria-label="Trace summary">
        <div><span>Total</span><strong>{traces.length || '—'}</strong></div>
        <div><span>Successful</span><strong className="text-success">{traces.length ? successful : '—'}</strong></div>
        <div><span>Failed</span><strong className="text-error">{traces.length ? failed : '—'}</strong></div>
        <div><span>Average duration</span><strong>{traces.length ? `${Math.round(average)}ms` : '—'}</strong></div>
      </section>
      <RecentTraces traces={traces} onSelect={onSelect} expanded />
    </div>
  )
}
