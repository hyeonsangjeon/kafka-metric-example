import type { ConnectionState, HealthState } from '../types'

type Status = ConnectionState | HealthState | 'streaming' | 'paused'

export function StatusDot({ status }: { status: Status }) {
  const active = ['connected', 'healthy', 'streaming'].includes(status)
  const warning = ['connecting', 'degraded', 'paused'].includes(status)
  return <span className={`status-dot ${active ? 'status-dot--active' : warning ? 'status-dot--warn' : 'status-dot--bad'}`} aria-hidden="true" />
}
