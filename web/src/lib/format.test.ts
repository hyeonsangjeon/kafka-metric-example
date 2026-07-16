import { describe, expect, it } from 'vitest'
import { buildSparklinePath, formatCompact, formatDuration, truncateMiddle } from './format'

describe('display formatters', () => {
  it('formats telemetry magnitudes without noisy precision', () => {
    expect(formatCompact(12_400)).toBe('12.4K')
    expect(formatCompact(3_010_000)).toBe('3.0M')
    expect(formatCompact(null)).toBe('—')
  })

  it('formats short and long latency', () => {
    expect(formatDuration(268)).toBe('268ms')
    expect(formatDuration(1_830)).toBe('1.83s')
  })

  it('builds stable sparkline paths and short trace labels', () => {
    expect(buildSparklinePath([10, 20, 15])).toMatch(/^M/)
    expect(buildSparklinePath([10])).toBe('')
    expect(truncateMiddle('1234567890', 7)).toBe('123…890')
  })
})
