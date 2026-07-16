import { AlertTriangle, RefreshCw, X } from 'lucide-react'
import { useCallback, useMemo, useState } from 'react'
import { AppShell } from './components/AppShell'
import { TraceDrawer } from './components/TraceDrawer'
import { useLabSession } from './hooks/useLabSession'
import { LiveLab } from './pages/LiveLab'
import { SystemPage } from './pages/SystemPage'
import { TracesPage } from './pages/TracesPage'
import type { AppView, TraceSummary } from './types'

export default function App() {
  const lab = useLabSession()
  const [view, setView] = useState<AppView>('live')
  const [selectedTraceId, setSelectedTraceId] = useState<string | null>(null)
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false)
  const selectedTrace = useMemo(
    () => lab.snapshot.traces.find((trace) => trace.id === selectedTraceId) ?? null,
    [lab.snapshot.traces, selectedTraceId],
  )
  const selectTraceById = useCallback((traceId: string) => setSelectedTraceId(traceId), [])
  const selectTrace = useCallback((trace: TraceSummary) => setSelectedTraceId(trace.id), [])
  const closeTrace = useCallback(() => setSelectedTraceId(null), [])

  return (
    <>
    <AppShell
      view={view}
      onViewChange={setView}
      connection={lab.connection}
      kafkaHealth={lab.snapshot.kafkaHealth}
      foundryHealth={lab.snapshot.foundryHealth}
      streamRate={lab.snapshot.streamRate}
      streamPaused={lab.streamPaused}
      onToggleStream={lab.toggleStream}
      onReset={() => void lab.reset().catch(() => undefined)}
      resetPending={lab.pendingAction === 'reset'}
      mobileMenuOpen={mobileMenuOpen}
      onMobileMenuChange={setMobileMenuOpen}
      providerMode={lab.config.mode}
      transportMode={lab.config.transport}
    >
      {view === 'live' ? (
        <LiveLab
          config={lab.config}
          snapshot={lab.snapshot}
          connection={lab.connection}
          pending={lab.pendingAction}
          streamPaused={lab.streamPaused}
          onStart={lab.start}
          onStop={lab.stop}
          onToggleStream={lab.toggleStream}
          onClearEvents={lab.clearEvents}
          onTraceSelect={selectTraceById}
        />
      ) : view === 'traces' ? (
        <TracesPage traces={lab.snapshot.traces} onSelect={selectTrace} />
      ) : (
        <SystemPage snapshot={lab.snapshot} connection={lab.connection} config={lab.config} />
      )}
    </AppShell>

    {lab.error ? (
      <div className="error-toast" role="alert">
        <AlertTriangle size={19} />
        <div><strong>Lab service unavailable</strong><span>{lab.error}</span></div>
        <button className="text-button" onClick={() => void lab.refresh().catch(() => undefined)}><RefreshCw size={15} /> Retry</button>
        <button className="icon-button" onClick={lab.dismissError} aria-label="Dismiss error"><X size={16} /></button>
      </div>
    ) : null}

    <TraceDrawer trace={selectedTrace} onClose={closeTrace} />
    </>
  )
}
