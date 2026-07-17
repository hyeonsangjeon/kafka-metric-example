import {
  Ban,
  Check,
  CircleCheck,
  CircleStop,
  CircleX,
  Clock3,
  Download,
  GitCompareArrows,
  LoaderCircle,
  LockKeyhole,
  TriangleAlert,
} from 'lucide-react'
import { useCallback, useState } from 'react'
import { formatCompact, formatDuration } from '../lib/format'
import type {
  ComparisonConfig,
  ComparisonPhase,
  ComparisonState,
  ComparisonStatus,
  LabConfig,
} from '../types'

interface CompareRunProps {
  config: ComparisonConfig | undefined
  providerMode: LabConfig['mode']
  comparison: ComparisonState | null
  onExport: (comparisonId: string) => Promise<ComparisonState>
}

const STATUS_LABELS: Record<ComparisonStatus, string> = {
  queued: 'Queued',
  running: 'Running',
  completed: 'Completed',
  failed: 'Failed',
  stopped: 'Stopped',
  unavailable: 'Unavailable',
}

function StatusIcon({ status, size = 16 }: { status: ComparisonStatus; size?: number }) {
  if (status === 'completed') return <CircleCheck aria-hidden="true" size={size} />
  if (status === 'failed') return <CircleX aria-hidden="true" size={size} />
  if (status === 'stopped') return <CircleStop aria-hidden="true" size={size} />
  if (status === 'unavailable') return <Ban aria-hidden="true" size={size} />
  if (status === 'running') return <LoaderCircle aria-hidden="true" className="spin" size={size} />
  return <Clock3 aria-hidden="true" size={size} />
}

function formatPercent(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value)) return '—'
  return `${value.toFixed(value >= 100 || Number.isInteger(value) ? 0 : 1)}%`
}

function formatSigned(value: number, formatter: (amount: number) => string): string {
  if (value === 0) return formatter(0)
  return `${value > 0 ? '+' : '−'}${formatter(Math.abs(value))}`
}

function shortRunId(runId: string): string {
  return runId.length > 12 ? `${runId.slice(0, 8)}…` : runId
}

function Delta({ value, percent, unit }: { value: number | null; percent: number | null; unit: 'latency' | 'tokens' }) {
  if (value == null && percent == null) return null
  const direction = (percent ?? value ?? 0) < 0 ? 'better' : (percent ?? value ?? 0) > 0 ? 'worse' : 'same'
  const arrow = direction === 'better' ? '↓' : direction === 'worse' ? '↑' : '→'
  const formattedValue = value == null
    ? null
    : formatSigned(value, unit === 'latency' ? formatDuration : (amount) => formatCompact(amount, 0))
  const formattedPercent = percent == null ? null : formatSigned(percent, (amount) => `${amount.toFixed(1)}%`)
  return (
    <span className={`comparison-delta comparison-delta--${direction}`}>
      <span aria-hidden="true">{arrow}</span>
      {[formattedValue, formattedPercent ? `(${formattedPercent})` : null].filter(Boolean).join(' ')}
      <span className="comparison-delta__baseline">vs fixed</span>
    </span>
  )
}

function ModelMix({ phase }: { phase: ComparisonPhase }) {
  const expectedTokenSamples = phase.completed || phase.requested
  if (!phase.models.length) {
    return (
      <div className="model-mix model-mix--empty">
        <div className="model-mix__heading"><span>Selected model mix</span><small>{phase.tokenSamples} / {expectedTokenSamples} token samples</small></div>
        <small>{phase.status === 'queued' ? 'Waiting for this profile' : 'No model telemetry reported'}</small>
      </div>
    )
  }

  const mixLabel = phase.models
    .map((model) => `${model.modelFamily} ${formatPercent(model.percentage)}`)
    .join(', ')
  return (
    <div className="model-mix">
      <div className="model-mix__heading"><span>Selected model mix</span><small>{phase.tokenSamples} / {expectedTokenSamples} token samples</small></div>
      <div className="model-mix__bar" role="img" aria-label={mixLabel}>
        {phase.models.map((model, index) => (
          <span
            key={`${model.modelFamily}-${index}`}
            className={`model-mix__segment model-mix__segment--${index % 3}`}
            style={{ width: `${Math.max(0, Math.min(100, model.percentage))}%` }}
          >
            {model.percentage >= 13 ? formatPercent(model.percentage) : null}
          </span>
        ))}
      </div>
      <div className="model-mix__legend">
        {phase.models.map((model, index) => (
          <span key={`${model.modelFamily}-legend-${index}`}>
            <i className={`model-mix__key model-mix__key--${index % 3}`} />
            {model.modelFamily} ({formatPercent(model.percentage)})
          </span>
        ))}
      </div>
    </div>
  )
}

function ComparisonCard({ phase }: { phase: ComparisonPhase }) {
  const isBaseline = phase.kind === 'fixed'
  return (
    <article
      className={`comparison-card comparison-card--${phase.status}`}
      aria-label={`${phase.label}: ${STATUS_LABELS[phase.status]}`}
      aria-busy={phase.status === 'running'}
    >
      <header className="comparison-card__header">
        <div>
          <h3>{phase.label}</h3>
          {phase.routeStrategy ? <span>{phase.routeStrategy.replaceAll('_', ' ')}</span> : null}
        </div>
        {isBaseline ? <span className="baseline-label">Baseline</span> : null}
      </header>
      <div className={`comparison-card__status comparison-status--${phase.status}`}>
        <StatusIcon status={phase.status} size={14} />
        <span>{STATUS_LABELS[phase.status]}</span>
        {phase.runId ? <code>{shortRunId(phase.runId)}</code> : null}
      </div>
      <dl className="comparison-metrics">
        <div><dt>Requested / completed / failed</dt><dd>{phase.requested.toLocaleString()} / {phase.completed.toLocaleString()} / {phase.failed.toLocaleString()}</dd></div>
        <div><dt>Success rate</dt><dd className={phase.successRate != null && phase.successRate >= 99 ? 'metric-good' : ''}>{formatPercent(phase.successRate)}</dd></div>
        <div><dt>P50 latency</dt><dd>{formatDuration(phase.p50LatencyMs)}</dd></div>
        <div>
          <dt>P95 latency</dt>
          <dd>
            <strong>{formatDuration(phase.p95LatencyMs)}</strong>
            {!isBaseline ? <Delta value={phase.p95DeltaMs} percent={phase.p95DeltaPercent} unit="latency" /> : null}
          </dd>
        </div>
        <div><dt>Retries</dt><dd>{phase.retries.toLocaleString()}</dd></div>
        <div><dt>Input tokens</dt><dd>{phase.tokenSamples ? phase.inputTokens.toLocaleString() : '—'}</dd></div>
        <div><dt>Output tokens</dt><dd>{phase.tokenSamples ? phase.outputTokens.toLocaleString() : '—'}</dd></div>
        <div>
          <dt>Total tokens</dt>
          <dd>
            <strong>{phase.tokenSamples ? (phase.inputTokens + phase.outputTokens).toLocaleString() : '—'}</strong>
            {!isBaseline ? <Delta value={phase.totalTokensDelta} percent={phase.totalTokensDeltaPercent} unit="tokens" /> : null}
          </dd>
        </div>
      </dl>
      <ModelMix phase={phase} />
    </article>
  )
}

function unavailableMessage(providerMode: LabConfig['mode'], config?: ComparisonConfig): string {
  if (config?.unavailableReason === 'provider_invocation_limit_too_low') {
    return 'The provider-call budget cannot cover one request for every profile.'
  }
  if (config?.unavailableReason === 'provider_exposes_fewer_than_two_profiles') {
    return providerMode === 'ollama'
      ? 'Ollama exposes one local fixed profile, so there are no router profiles to compare.'
      : 'This provider exposes fewer than two comparison profiles.'
  }
  if (config?.unavailableReason && !/^[a-z0-9_]+$/.test(config.unavailableReason)) {
    return config.unavailableReason
  }
  if (providerMode === 'ollama') {
    return 'Profile comparison is unavailable in Ollama mode. Single-profile workloads remain available.'
  }
  return 'This provider has not exposed a bounded comparison profile set.'
}

export function CompareRun({ config, providerMode, comparison, onExport }: CompareRunProps) {
  const [exporting, setExporting] = useState(false)
  const [exportError, setExportError] = useState<string | null>(null)
  const isAvailable = config?.enabled === true
  const isBusy = comparison?.status === 'queued' || comparison?.status === 'running'
  const status = comparison?.status ?? (isAvailable ? null : 'unavailable')
  const comparisonId = comparison?.comparisonId

  const exportJson = useCallback(async () => {
    if (!comparisonId) return
    setExporting(true)
    setExportError(null)
    try {
      const safeComparison = await onExport(comparisonId)
      const blob = new Blob([`${JSON.stringify(safeComparison, null, 2)}\n`], { type: 'application/json' })
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = `comparison-${safeComparison.comparisonId}.json`
      document.body.append(link)
      link.click()
      link.remove()
      URL.revokeObjectURL(url)
    } catch (error) {
      setExportError(error instanceof Error ? error.message : 'The safe comparison export could not be loaded.')
    } finally {
      setExporting(false)
    }
  }, [comparisonId, onExport])

  return (
    <section
      className={`panel compare-run ${status ? `compare-run--${status}` : 'compare-run--idle'}`}
      aria-labelledby="compare-run-title"
      aria-busy={isBusy}
    >
      <div className="panel-heading compare-run__heading">
        <div className="compare-run__title">
          <h2 id="compare-run-title"><GitCompareArrows aria-hidden="true" size={17} /> Profile comparison</h2>
          {status ? (
            <span className={`comparison-status-badge comparison-status--${status}`}>
              <StatusIcon status={status} size={13} />
              {STATUS_LABELS[status]}
            </span>
          ) : null}
        </div>
        <div className="compare-run__tools">
          {comparison ? (
            <span className="comparison-call-budget">
              {comparison.plannedRequests.toLocaleString()} planned
              <i aria-hidden="true" />
              {comparison.providerInvocations.toLocaleString()} / {comparison.providerInvocationLimit.toLocaleString()} provider calls
            </span>
          ) : null}
          <button
            className="export-button"
            onClick={() => void exportJson()}
            disabled={!comparison?.comparisonId || exporting}
          >
            {exporting ? <LoaderCircle aria-hidden="true" className="spin" size={15} /> : <Download aria-hidden="true" size={15} />}
            Export JSON
          </button>
        </div>
      </div>

      <span className="sr-only" role="status" aria-live="polite">
        {comparison
          ? `Comparison ${comparison.comparisonId} ${STATUS_LABELS[comparison.status].toLowerCase()}${comparison.currentProfile ? `: ${comparison.currentProfile}` : ''}.`
          : ''}
      </span>

      {!isAvailable && !comparison ? (
        <div className="comparison-empty comparison-empty--unavailable">
          <Ban aria-hidden="true" size={24} />
          <div>
            <strong>Comparison unavailable</strong>
            <p>{unavailableMessage(providerMode, config)}</p>
          </div>
        </div>
      ) : !comparison ? (
        <div className="comparison-empty">
          <GitCompareArrows aria-hidden="true" size={24} />
          <div>
            <strong>Ready for a controlled profile comparison</strong>
            <p>Each profile runs sequentially with the same workload, scenario, traffic, and prompt.</p>
          </div>
          <ul aria-label="Comparison profiles">
            {config?.profiles.map((profile) => <li key={profile.id}><Check aria-hidden="true" size={13} /> {profile.label}</li>)}
          </ul>
        </div>
      ) : (
        <div className="compare-run__body">
          <ol className="comparison-phases" aria-label="Comparison progress">
            {comparison.phases.map((phase) => {
              const current = comparison.currentProfile === phase.profile && isBusy
              return (
                <li
                  key={phase.profile}
                  className={`comparison-phase comparison-phase--${phase.status} ${current ? 'comparison-phase--current' : ''}`}
                  aria-current={current ? 'step' : undefined}
                >
                  <StatusIcon status={phase.status} size={17} />
                  <span><strong>{phase.label}</strong><small>{STATUS_LABELS[phase.status]}{phase.runId ? ` · ${shortRunId(phase.runId)}` : ''}</small></span>
                </li>
              )
            })}
          </ol>

          {comparison.status === 'failed' ? (
            <div className="comparison-notice comparison-notice--failed" role="alert">
              <TriangleAlert aria-hidden="true" size={16} />
              Comparison stopped after a profile failed. Completed telemetry remains exportable.
            </div>
          ) : comparison.status === 'stopped' ? (
            <div className="comparison-notice">
              <CircleStop aria-hidden="true" size={16} />
              Comparison stopped by request. Completed telemetry remains available.
            </div>
          ) : null}

          <div className="comparison-grid">
            {comparison.phases.map((phase) => <ComparisonCard key={phase.profile} phase={phase} />)}
          </div>
        </div>
      )}

      {exportError ? <p className="comparison-export-error" role="alert">{exportError}</p> : null}
      <footer className="comparison-privacy">
        <LockKeyhole aria-hidden="true" size={14} />
        <span><strong>Telemetry only.</strong> Prompt and response bodies are never retained or included in the JSON export.</span>
      </footer>
    </section>
  )
}
