import { ArrowDownRight, ArrowUpRight } from 'lucide-react'
import { buildSparklinePath, formatCompact } from '../lib/format'
import type { KpiValue } from '../types'

function displayValue(kpi: KpiValue): string {
  if (kpi.value == null) return '—'
  if (kpi.unit === '%' && kpi.value < 100) return kpi.value.toFixed(1)
  if (kpi.unit === 'ms' && kpi.value < 1_000) return Math.round(kpi.value).toString()
  return formatCompact(kpi.value, kpi.value >= 100 ? 1 : 1)
}

export function KpiRail({ kpis }: { kpis: KpiValue[] }) {
  return (
    <section className="kpi-scroller" aria-label="Live key metrics">
      <div className="kpi-rail">
        {kpis.map((kpi) => {
          const path = buildSparklinePath(kpi.series)
          const changeUp = (kpi.change ?? 0) >= 0
          return (
            <article key={kpi.key} className={`kpi-card kpi-card--${kpi.tone}`}>
              <div className="kpi-card__label">{kpi.label}</div>
              <div className="kpi-card__value-row">
                <strong>{displayValue(kpi)} {kpi.value != null && kpi.unit ? <small>{kpi.unit}</small> : null}</strong>
                {kpi.change != null ? (
                  <span className={changeUp ? 'change change--up' : 'change change--down'}>
                    {changeUp ? <ArrowUpRight size={13} /> : <ArrowDownRight size={13} />}
                    {Math.abs(kpi.change).toFixed(1)}%
                  </span>
                ) : null}
              </div>
              <div className="sparkline" aria-hidden="true">
                {path ? (
                  <svg viewBox="0 0 120 28" preserveAspectRatio="none">
                    <path className="sparkline__shadow" d={path} />
                    <path className="sparkline__line" d={path} />
                  </svg>
                ) : <span className="sparkline__empty" />}
              </div>
            </article>
          )
        })}
      </div>
    </section>
  )
}
