import type { ConnectionState, LabConfig, LabSnapshot, RunInput } from '../types'
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
  pending: 'run' | 'stop' | 'reset' | null
  streamPaused: boolean
  onStart: (input: RunInput) => Promise<void>
  onStop: () => Promise<void>
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
  onToggleStream,
  onClearEvents,
  onTraceSelect,
}: LiveLabProps) {
  return (
    <div className="live-lab page-enter">
      <RunConsole config={config} run={snapshot.run} connection={connection} pending={pending} onStart={onStart} onStop={onStop} />
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
