import { Check, ChevronRight, CircleAlert, CircleX, Filter, Info, Pause, Play, Trash2, TriangleAlert } from 'lucide-react'
import { useMemo, useState } from 'react'
import { formatClock } from '../lib/format'
import type { LabEvent } from '../types'

type FilterValue = 'all' | 'warning' | 'error' | 'success'

interface LiveEventsProps {
  events: LabEvent[]
  paused: boolean
  connected: boolean
  onTogglePause: () => void
  onClear: () => void
  onTraceSelect: (traceId: string) => void
}

const EVENT_ICONS = {
  success: Check,
  warning: TriangleAlert,
  error: CircleX,
  info: Info,
}

export function LiveEvents({ events, paused, connected, onTogglePause, onClear, onTraceSelect }: LiveEventsProps) {
  const [filter, setFilter] = useState<FilterValue>('all')
  const filtered = useMemo(() => filter === 'all' ? events : events.filter((event) => event.level === filter), [events, filter])

  return (
    <section className="panel live-events" aria-labelledby="events-title">
      <div className="panel-heading live-events__heading">
        <div>
          <h2 id="events-title">Live events</h2>
          <p><span className={`live-indicator ${connected && !paused ? 'live-indicator--active' : ''}`} /> {paused ? 'Stream paused' : connected ? 'Streaming' : 'Disconnected'}</p>
        </div>
        <button className="icon-button" onClick={onClear} aria-label="Clear events" title="Clear events"><Trash2 size={16} /></button>
      </div>
      <div className="event-toolbar">
        <label className="event-filter">
          <Filter size={16} />
          <select value={filter} onChange={(event) => setFilter(event.target.value as FilterValue)}>
            <option value="all">All events</option>
            <option value="warning">Warnings</option>
            <option value="error">Errors</option>
            <option value="success">Success</option>
          </select>
        </label>
        <button className="text-button" onClick={onTogglePause}>{paused ? <Play size={15} /> : <Pause size={15} />} {paused ? 'Resume' : 'Pause'}</button>
      </div>
      <div className="event-list">
        {filtered.length ? filtered.slice(0, 24).map((event) => {
          const Icon = EVENT_ICONS[event.level]
          const interactive = Boolean(event.traceId)
          return (
            <button
              key={event.id}
              className={`event-row event-row--${event.level}`}
              onClick={() => event.traceId && onTraceSelect(event.traceId)}
              disabled={!interactive}
            >
              <span className="event-row__icon"><Icon size={19} /></span>
              <time>{formatClock(event.timestamp)}</time>
              <span className="event-row__copy"><strong>{event.title}</strong><small>{event.detail || event.category || 'Telemetry event'}</small></span>
              <span className={`level-badge level-badge--${event.level}`}>{event.level === 'success' ? 'OK' : event.level.toUpperCase()}</span>
              {interactive ? <ChevronRight size={18} /> : null}
            </button>
          )
        }) : (
          <div className="list-empty">
            <CircleAlert size={21} />
            <strong>{events.length ? 'No events match this filter' : 'No live events yet'}</strong>
            <span>{connected ? 'Events appear here as a workload runs.' : 'Reconnect to the lab to receive events.'}</span>
          </div>
        )}
      </div>
    </section>
  )
}
