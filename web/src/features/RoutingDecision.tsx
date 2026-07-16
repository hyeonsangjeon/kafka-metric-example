import { GitFork } from 'lucide-react'
import { formatCompact, formatDuration } from '../lib/format'
import type { RoutingDecision as RoutingDecisionData } from '../types'

function friendly(value: string): string {
  return value.replaceAll(/[_-]+/g, ' ').replace(/\b\w/g, (letter) => letter.toUpperCase())
}

export function RoutingDecision({ routing }: { routing: RoutingDecisionData | null }) {
  const hasDecision = routing !== null && (routing.requestsRouted > 0 || routing.routes.length > 0)

  return (
    <section className="panel routing-decision" aria-labelledby="routing-decision-title">
      <div className="panel-heading routing-decision__heading">
        <div>
          <h2 id="routing-decision-title"><GitFork size={16} /> Routing decision</h2>
          <p>{routing?.explanation || 'See how the selected execution profile handled this run.'}</p>
        </div>
        {hasDecision ? <span className="routing-decision__status"><i /> Decision telemetry</span> : null}
      </div>

      {hasDecision && routing ? (
        <div className="routing-decision__body">
          <dl className="routing-summary">
            <div><dt>Strategy</dt><dd>{friendly(routing.strategy)}</dd></div>
            <div><dt>Requests routed</dt><dd>{formatCompact(routing.requestsRouted, 0)}</dd></div>
          </dl>

          <div className="route-mix" role="table" aria-label="Model route performance">
            <div className="route-mix__head" role="row">
              <span>Selected route</span><span>Model mix</span><span>Requests</span><span>P95</span><span>Success</span>
            </div>
            {routing.routes.map((route, index) => (
              <div className="route-mix__row" role="row" key={route.id}>
                <strong><i className={`route-swatch route-swatch--${index % 3}`} />{friendly(route.label)}</strong>
                <span className="route-share">
                  <i className={`route-share__fill route-share__fill--${index % 3}`} style={{ width: `${route.share}%` }} />
                  <small>{route.share.toFixed(route.share >= 10 ? 0 : 1)}%</small>
                </span>
                <span data-label="Requests">{formatCompact(route.requests, 0)}</span>
                <span data-label="P95">{formatDuration(route.p95LatencyMs)}</span>
                <span data-label="Success" className={route.successRate != null && route.successRate < 95 ? 'route-value--warn' : 'route-value--good'}>
                  {route.successRate == null ? '—' : `${route.successRate.toFixed(1)}%`}
                </span>
              </div>
            ))}
            {!routing.routes.length ? <div className="route-mix__empty">The provider reported a decision, but no route breakdown.</div> : null}
          </div>
        </div>
      ) : (
        <div className="routing-decision__empty">
          <GitFork size={20} />
          <div><strong>No routing decision yet</strong><span>Run a workload to compare fixed deployment and Model Router behavior.</span></div>
        </div>
      )}
    </section>
  )
}
