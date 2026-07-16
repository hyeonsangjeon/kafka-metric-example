import { CheckCircle2, CircleX, Clock3, Copy, Database, GitFork, Hash, LockKeyhole, TriangleAlert, X } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { formatClock, formatDuration, truncateMiddle } from '../lib/format'
import type { TraceSummary } from '../types'

interface TraceDrawerProps {
  trace: TraceSummary | null
  onClose: () => void
}

const STATUS_ICON = {
  ok: CheckCircle2,
  warning: TriangleAlert,
  error: CircleX,
}

export function TraceDrawer({ trace, onClose }: TraceDrawerProps) {
  const [copiedTraceId, setCopiedTraceId] = useState<string | null>(null)
  const drawerRef = useRef<HTMLElement>(null)
  const traceId = trace?.id

  useEffect(() => {
    if (!traceId) return
    const previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const focusFrame = window.requestAnimationFrame(() => drawerRef.current?.focus())
    const handleKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onClose()
        return
      }
      if (event.key !== 'Tab' || !drawerRef.current) return
      const focusable = Array.from(drawerRef.current.querySelectorAll<HTMLElement>(
        'button:not([disabled]), a[href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
      ))
      if (!focusable.length) {
        event.preventDefault()
        drawerRef.current.focus()
        return
      }
      const first = focusable[0]
      const last = focusable[focusable.length - 1]
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first.focus()
      }
    }
    document.addEventListener('keydown', handleKey)
    return () => {
      window.cancelAnimationFrame(focusFrame)
      document.removeEventListener('keydown', handleKey)
      previousFocus?.focus()
    }
  }, [onClose, traceId])

  if (!trace) return null
  const StatusIcon = STATUS_ICON[trace.status]
  const copied = copiedTraceId === trace.id
  const copyId = async () => {
    await navigator.clipboard?.writeText(trace.id)
    setCopiedTraceId(trace.id)
  }

  return (
    <div className="drawer-layer" role="presentation">
      <button className="drawer-backdrop" onClick={onClose} aria-label="Close trace details" />
      <aside
        ref={drawerRef}
        className="trace-drawer"
        role="dialog"
        aria-modal="true"
        aria-labelledby="trace-drawer-title"
        tabIndex={-1}
      >
        <header className="trace-drawer__header">
          <div>
            <span className={`trace-result trace-result--${trace.status}`}><StatusIcon size={16} /> {trace.status === 'ok' ? 'Completed' : trace.status}</span>
            <h2 id="trace-drawer-title">Trace details</h2>
          </div>
          <button className="icon-button" onClick={onClose} aria-label="Close trace details"><X size={20} /></button>
        </header>

        <div className="trace-drawer__id">
          <span>Trace ID</span>
          <code>{trace.id}</code>
          <button className="icon-button" onClick={copyId} aria-label="Copy trace ID"><Copy size={15} /></button>
          {copied ? <small>Copied</small> : null}
        </div>

        <dl className="trace-facts">
          <div><dt><Clock3 size={15} /> Started</dt><dd>{formatClock(trace.startedAt)}</dd></div>
          <div><dt><Clock3 size={15} /> Duration</dt><dd>{formatDuration(trace.durationMs)}</dd></div>
          <div><dt><Database size={15} /> Partition</dt><dd>{trace.metadata?.partition ?? '—'}</dd></div>
          <div><dt><Hash size={15} /> Attempt</dt><dd>{trace.metadata?.attempt ?? 1}</dd></div>
          <div><dt><GitFork size={15} /> Selected route</dt><dd>{trace.metadata?.selectedRoute || '—'}</dd></div>
          <div><dt><GitFork size={15} /> Model family</dt><dd>{trace.metadata?.selectedModelFamily || '—'}</dd></div>
        </dl>

        <section className="drawer-section">
          <div className="drawer-section__heading"><h3>Waterfall</h3><span>{formatDuration(trace.durationMs)}</span></div>
          <div className="waterfall-axis"><span>0ms</span><span>{formatDuration(trace.durationMs / 2)}</span><span>{formatDuration(trace.durationMs)}</span></div>
          <div className="waterfall-list">
            {trace.spans.length ? trace.spans.map((span) => {
              const left = Math.min(88, (span.startedAtMs / Math.max(trace.durationMs, 1)) * 100)
              const width = Math.max(4, Math.min(100 - left, (span.durationMs / Math.max(trace.durationMs, 1)) * 100))
              return (
                <div className="waterfall-row" key={span.id}>
                  <div className="waterfall-row__label"><strong>{span.name}</strong><span>{span.service}</span></div>
                  <div className="waterfall-row__track"><i className={`waterfall-row__bar waterfall-row__bar--${span.status}`} style={{ left: `${left}%`, width: `${width}%` }}><span>{formatDuration(span.durationMs)}</span></i></div>
                </div>
              )
            }) : <div className="drawer-empty">This trace has no span-level timing yet.</div>}
          </div>
        </section>

        <section className="drawer-section">
          <div className="drawer-section__heading"><h3>Safe metadata</h3></div>
          <dl className="metadata-list">
            <div><dt>Run</dt><dd>{trace.metadata?.runId ? truncateMiddle(trace.metadata.runId, 28) : '—'}</dd></div>
            <div><dt>Workload</dt><dd>{trace.workload || '—'}</dd></div>
            <div><dt>Scenario</dt><dd>{trace.scenario?.replaceAll('_', ' ') || '—'}</dd></div>
            <div><dt>Execution profile</dt><dd>{trace.metadata?.modelProfile || '—'}</dd></div>
            <div><dt>Route strategy</dt><dd>{trace.metadata?.routeStrategy || '—'}</dd></div>
            <div><dt>Input / output tokens</dt><dd>{trace.metadata?.inputTokens ?? '—'} / {trace.metadata?.outputTokens ?? '—'}</dd></div>
            <div><dt>Input / output</dt><dd>{trace.metadata?.inputChars ?? '—'} / {trace.metadata?.outputChars ?? '—'} chars</dd></div>
            <div><dt>Prompt hash</dt><dd><code>{trace.metadata?.promptHash ? truncateMiddle(trace.metadata.promptHash, 30) : '—'}</code></dd></div>
            {trace.metadata?.errorCode ? <div><dt>Error code</dt><dd className="text-error">{trace.metadata.errorCode}</dd></div> : null}
          </dl>
        </section>

        <div className="drawer-privacy"><LockKeyhole size={16} /><span>Prompt and response bodies are intentionally absent. Only hashes, sizes, timing, and delivery metadata are observable.</span></div>
      </aside>
    </div>
  )
}
