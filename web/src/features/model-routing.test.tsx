import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { LabConfig } from '../types'
import { RoutingDecision } from './RoutingDecision'
import { RunConsole } from './RunConsole'

const config: LabConfig = {
  mode: 'simulated',
  transport: 'memory',
  cloudReady: false,
  maxTrafficPerRun: 20,
  workloads: [{ id: 'chat', label: 'Chat' }],
  scenarios: [{ id: 'healthy', label: 'Healthy' }],
  models: [
    { id: 'fixed', label: 'Fixed deployment' },
    { id: 'router-balanced', label: 'Model Router · Balanced' },
  ],
  modelProfiles: [
    { id: 'fixed', label: 'Fixed deployment', strategy: 'fixed', kind: 'fixed' },
    { id: 'router-balanced', label: 'Model Router · Balanced', strategy: 'balanced', kind: 'router' },
  ],
  defaults: { workloadId: 'chat', scenarioId: 'healthy', modelProfile: 'fixed', requestRate: 8 },
}

describe('model routing UI', () => {
  afterEach(cleanup)

  it('submits the selected Model Router profile', async () => {
    const onStart = vi.fn().mockResolvedValue(undefined)
    render(
      <RunConsole
        config={config}
        run={null}
        connection="connected"
        pending={null}
        onStart={onStart}
        onStop={vi.fn().mockResolvedValue(undefined)}
      />,
    )

    fireEvent.click(screen.getByRole('radio', { name: /Model Router · Balanced/i }))
    fireEvent.click(screen.getByRole('button', { name: /Run workload/i }))

    await waitFor(() => expect(onStart).toHaveBeenCalledWith(expect.objectContaining({
      modelProfile: 'router-balanced',
      modelId: 'router-balanced',
    })))
  })

  it('renders route mix performance and the empty state', () => {
    const { rerender } = render(<RoutingDecision routing={{
      strategy: 'balanced',
      requestsRouted: 24,
      explanation: 'Balanced per request.',
      routes: [{ id: 'fast', label: 'Fast', requests: 14, share: 58.3, p95LatencyMs: 168, successRate: 99.6 }],
    }} />)

    expect(screen.getByText('Balanced')).toBeInTheDocument()
    expect(screen.getByText('Fast')).toBeInTheDocument()
    expect(screen.getByText('168ms')).toBeInTheDocument()
    expect(screen.getByText('99.6%')).toBeInTheDocument()

    rerender(<RoutingDecision routing={null} />)
    expect(screen.getByText('No routing decision yet')).toBeInTheDocument()
  })
})
