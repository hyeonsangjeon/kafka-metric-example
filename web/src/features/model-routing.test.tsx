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
  comparison: {
    enabled: true,
    profiles: [
      { id: 'fixed', label: 'Fixed deployment', kind: 'fixed' },
      { id: 'router-balanced', label: 'Model Router · Balanced', kind: 'router', routeStrategy: 'balanced' },
    ],
    maxTrafficPerProfile: 5,
    providerInvocationLimit: 12,
  },
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
        comparison={null}
        connection="connected"
        pending={null}
        onStart={onStart}
        onStop={vi.fn().mockResolvedValue(undefined)}
        onCompare={vi.fn().mockResolvedValue(undefined)}
        onStopComparison={vi.fn().mockResolvedValue(undefined)}
      />,
    )

    fireEvent.click(screen.getByRole('radio', { name: /Model Router · Balanced/i }))
    fireEvent.click(screen.getByRole('button', { name: /Run workload/i }))

    await waitFor(() => expect(onStart).toHaveBeenCalledWith(expect.objectContaining({
      modelProfile: 'router-balanced',
      modelId: 'router-balanced',
    })))
  })

  it('starts one bounded comparison with the same console input', async () => {
    const onCompare = vi.fn().mockResolvedValue(undefined)
    render(
      <RunConsole
        config={config}
        run={null}
        comparison={null}
        connection="connected"
        pending={null}
        onStart={vi.fn().mockResolvedValue(undefined)}
        onStop={vi.fn().mockResolvedValue(undefined)}
        onCompare={onCompare}
        onStopComparison={vi.fn().mockResolvedValue(undefined)}
      />,
    )

    fireEvent.change(screen.getByPlaceholderText(/safe default/i), { target: { value: 'Compare this exact prompt.' } })
    fireEvent.click(screen.getByRole('button', { name: 'Compare profiles' }))

    await waitFor(() => expect(onCompare).toHaveBeenCalledTimes(1))
    expect(onCompare).toHaveBeenCalledWith({
      workload: 'chat',
      scenario: 'healthy',
      traffic: 5,
      prompt: 'Compare this exact prompt.',
    })
  })

  it('keeps Stop comparison available while its child run is active', async () => {
    const onStopComparison = vi.fn().mockResolvedValue(undefined)
    render(
      <RunConsole
        config={config}
        run={{ id: 'run-child', status: 'running' }}
        comparison={{
          comparisonId: 'comparison-1',
          status: 'running',
          workload: 'chat',
          trafficPerProfile: 5,
          profiles: ['fixed', 'router-balanced'],
          currentProfile: 'fixed',
          plannedRequests: 10,
          providerInvocations: 2,
          providerInvocationLimit: 12,
          phases: [],
        }}
        connection="connected"
        pending={null}
        onStart={vi.fn().mockResolvedValue(undefined)}
        onStop={vi.fn().mockResolvedValue(undefined)}
        onCompare={vi.fn().mockResolvedValue(undefined)}
        onStopComparison={onStopComparison}
      />,
    )

    const stopComparison = screen.getByRole('button', { name: 'Stop comparison' })
    expect(stopComparison).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Run workload' })).toBeDisabled()
    fireEvent.click(stopComparison)

    await waitFor(() => expect(onStopComparison).toHaveBeenCalledTimes(1))
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
