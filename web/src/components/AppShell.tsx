import {
  Activity,
  GitBranch,
  Menu,
  Pause,
  Play,
  RotateCcw,
  ServerCog,
  ShieldCheck,
  X,
} from 'lucide-react'
import type { PropsWithChildren } from 'react'
import { BrandMark } from './BrandMark'
import { StatusDot } from './StatusDot'
import type { AppView, ConnectionState, HealthState } from '../types'
import { formatCompact } from '../lib/format'

const NAV_ITEMS = [
  { id: 'live' as const, label: 'Live Lab', icon: Activity },
  { id: 'traces' as const, label: 'Traces', icon: GitBranch },
  { id: 'system' as const, label: 'System', icon: ServerCog },
]

interface AppShellProps extends PropsWithChildren {
  view: AppView
  onViewChange: (view: AppView) => void
  connection: ConnectionState
  kafkaHealth: HealthState
  foundryHealth: HealthState
  streamRate: number | null
  streamPaused: boolean
  onToggleStream: () => void
  onReset: () => void
  resetPending: boolean
  resetDisabled: boolean
  mobileMenuOpen: boolean
  onMobileMenuChange: (open: boolean) => void
  providerMode: 'simulated' | 'ollama' | 'foundry' | 'unknown'
  transportMode: 'memory' | 'kafka' | 'unknown'
}

function readableHealth(health: HealthState): string {
  if (health === 'unknown') return 'Awaiting data'
  if (health === 'healthy') return 'Ready'
  return health[0].toUpperCase() + health.slice(1)
}

export function AppShell({
  children,
  view,
  onViewChange,
  connection,
  kafkaHealth,
  foundryHealth,
  streamRate,
  streamPaused,
  onToggleStream,
  onReset,
  resetPending,
  resetDisabled,
  mobileMenuOpen,
  onMobileMenuChange,
  providerMode,
  transportMode,
}: AppShellProps) {
  const providerLabel = providerMode === 'simulated' ? 'Simulator' : providerMode === 'ollama' ? 'Ollama' : providerMode === 'foundry' ? 'Foundry' : 'AI provider'
  const transportLabel = transportMode === 'memory' ? 'Memory transport' : 'Kafka'
  const navigate = (target: AppView) => {
    onViewChange(target)
    onMobileMenuChange(false)
  }

  return (
    <div className="app-shell">
      <aside className={`sidebar ${mobileMenuOpen ? 'sidebar--open' : ''}`}>
        <div className="sidebar__brand-row">
          <BrandMark />
          <button className="icon-button sidebar__close" onClick={() => onMobileMenuChange(false)} aria-label="Close navigation">
            <X size={20} />
          </button>
        </div>
        <nav className="primary-nav" aria-label="Primary navigation">
          {NAV_ITEMS.map((item) => {
            const Icon = item.icon
            return (
              <button
                key={item.id}
                className={`primary-nav__item ${view === item.id ? 'primary-nav__item--active' : ''}`}
                onClick={() => navigate(item.id)}
                aria-current={view === item.id ? 'page' : undefined}
              >
                <Icon size={19} strokeWidth={1.8} />
                <span>{item.label}</span>
              </button>
            )
          })}
        </nav>
        <div className="sidebar__footer">
          <div className="privacy-mini"><ShieldCheck size={15} /><span>Payload bodies private</span></div>
          <div className="lab-running"><StatusDot status={connection} /><span>{connection === 'connected' ? 'Lab connected' : 'Lab offline'}</span></div>
          <span className="version">v1.2.0</span>
        </div>
      </aside>

      {mobileMenuOpen ? <button className="sidebar-backdrop" onClick={() => onMobileMenuChange(false)} aria-label="Close navigation" /> : null}

      <div className="app-frame">
        <header className="mobile-header">
          <BrandMark compact />
          <div className="connection-button" aria-label={`Connection: ${connection}`}>
            <StatusDot status={connection} />
            <span>{connection === 'connected' ? 'Connected' : connection === 'connecting' ? 'Connecting' : 'Offline'}</span>
          </div>
          <button className="icon-button mobile-menu-button" onClick={() => onMobileMenuChange(true)} aria-label="Open navigation">
            <Menu size={23} />
          </button>
        </header>

        <header className="topbar">
          <div className="topbar__cluster" aria-label="Service status">
            <div className="status-chip">
              <StatusDot status={foundryHealth} />
              <strong>{providerLabel}</strong>
              <span>{readableHealth(foundryHealth)}</span>
            </div>
            <div className="status-chip">
              <StatusDot status={kafkaHealth} />
              <strong>{transportLabel}</strong>
              <span>{readableHealth(kafkaHealth)}</span>
            </div>
            <div className="status-chip">
              <StatusDot status={streamPaused ? 'paused' : connection === 'connected' ? 'streaming' : connection} />
              <strong>Live stream</strong>
              <span>{streamPaused ? 'Paused' : connection === 'connected' ? `${formatCompact(streamRate)} ev/s` : connection}</span>
            </div>
          </div>
          <div className="topbar__actions">
            <button className="toolbar-button" onClick={onToggleStream}>
              {streamPaused ? <Play size={15} /> : <Pause size={15} />}
              <span>{streamPaused ? 'Resume stream' : 'Pause stream'}</span>
            </button>
            <button className="toolbar-button" onClick={onReset} disabled={resetDisabled}>
              <RotateCcw size={15} className={resetPending ? 'spin' : ''} />
              <span>Reset session</span>
            </button>
          </div>
        </header>

        <main className="app-content">{children}</main>

        <nav className="mobile-tabs" aria-label="Mobile navigation">
          {NAV_ITEMS.map((item) => {
            const Icon = item.icon
            return (
              <button
                key={item.id}
                className={view === item.id ? 'mobile-tabs__item--active' : ''}
                onClick={() => navigate(item.id)}
                aria-current={view === item.id ? 'page' : undefined}
              >
                <Icon size={22} strokeWidth={1.8} />
                <span>{item.label}</span>
              </button>
            )
          })}
        </nav>
      </div>
    </div>
  )
}
