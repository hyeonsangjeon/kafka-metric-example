import type { ComparisonInput, ComparisonState, ConnectionState, LabConfig, LabSnapshot, RunInput } from '../types'
import { CompareRun } from '../features/CompareRun'
import { KpiRail } from '../features/KpiRail'
import { LiveEvents } from '../features/LiveEvents'
import { RecentTraces } from '../features/RecentTraces'
import { RunConsole } from '../features/RunConsole'
import { RoutingDecision } from '../features/RoutingDecision'
import { TelemetryRiver } from '../features/TelemetryRiver'

interface LiveLabProps {
  config: LabConfig
  snapshot: LabSnapshot
  connection: ConnectionState
  pending: 'run' | 'stop' | 'compare' | 'stop-comparison' | 'reset' | null
  streamPaused: boolean
  onStart: (input: RunInput) => Promise<void>
  onStop: () => Promise<void>
  onCompare: (input: ComparisonInput) => Promise<void>
  onStopComparison: () => Promise<void>
  onExportComparison: (comparisonId: string) => Promise<ComparisonState>
  onToggleStream: () => void
  onClearEvents: () => void
  onTraceSelect: (traceId: string) => void
}

export function LiveLab({
  config,
  snapshot,
  connection,
  pending,
  streamPaused,
  onStart,
  onStop,
  onCompare,
  onStopComparison,
  onExportComparison,
  onToggleStream,
  onClearEvents,
  onTraceSelect,
}: LiveLabProps) {
  return (
    <div className="live-lab page-enter">
      <RunConsole
        config={config}
        run={snapshot.run}
        comparison={snapshot.comparison}
        connection={connection}
        pending={pending}
        onStart={onStart}
        onStop={onStop}
        onCompare={onCompare}
        onStopComparison={onStopComparison}
      />
      <CompareRun
        config={config.comparison}
        providerMode={config.mode}
        comparison={snapshot.comparison}
        onExport={onExportComparison}
      />
      <KpiRail kpis={snapshot.kpis} />
      <RoutingDecision routing={snapshot.routing} />
      <div className="live-workspace">
        <TelemetryRiver
          partitions={snapshot.partitions}
          kpis={snapshot.kpis}
          run={snapshot.run}
          connected={connection === 'connected'}
          providerMode={config.mode}
          transportMode={config.transport}
        />
        <LiveEvents
          events={snapshot.events}
          paused={streamPaused}
          connected={connection === 'connected'}
          onTogglePause={onToggleStream}
          onClear={onClearEvents}
          onTraceSelect={onTraceSelect}
        />
      </div>
      <RecentTraces traces={snapshot.traces} onSelect={(trace) => onTraceSelect(trace.id)} />
    </div>
  )
}
