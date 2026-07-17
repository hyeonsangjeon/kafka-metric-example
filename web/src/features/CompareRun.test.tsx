import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ComparisonConfig, ComparisonPhase, ComparisonState } from '../types'
import { CompareRun } from './CompareRun'

const profiles: ComparisonConfig['profiles'] = [
  { id: 'fixed', label: 'Fixed deployment', kind: 'fixed' },
  { id: 'router-default', label: 'Router default', kind: 'router', routeStrategy: 'balanced' },
  { id: 'router-advanced', label: 'Router advanced', kind: 'router', routeStrategy: 'cost' },
]

const config: ComparisonConfig = {
  enabled: true,
  profiles,
  maxTrafficPerProfile: 3,
  providerInvocationLimit: 10,
}

function phase(overrides: Partial<ComparisonPhase>): ComparisonPhase {
  return {
    profile: 'fixed',
    label: 'Fixed deployment',
    kind: 'fixed',
    status: 'completed',
    runId: 'run-fixed',
    requested: 3,
    completed: 3,
    failed: 0,
    retries: 0,
    successRate: 100,
    p50LatencyMs: 1_210,
    p95LatencyMs: 3_820,
    inputTokens: 4_512,
    outputTokens: 1_842,
    tokenSamples: 3,
    models: [{ modelFamily: 'gpt-4o', count: 3, percentage: 100 }],
    p95DeltaMs: null,
    p95DeltaPercent: null,
    totalTokensDelta: null,
    totalTokensDeltaPercent: null,
    ...overrides,
  }
}

const completedComparison: ComparisonState = {
  comparisonId: 'comparison-20260717',
  status: 'completed',
  workload: 'chat',
  scenario: 'healthy',
  trafficPerProfile: 3,
  profiles: profiles.map((profile) => profile.id),
  plannedRequests: 9,
  providerInvocations: 9,
  providerInvocationLimit: 10,
  startedAt: '2026-07-17T04:00:00.000Z',
  completedAt: '2026-07-17T04:01:00.000Z',
  phases: [
    phase({}),
    phase({
      profile: 'router-default',
      label: 'Router default',
      kind: 'router',
      routeStrategy: 'balanced',
      runId: 'run-default',
      p50LatencyMs: 930,
      p95LatencyMs: 2_440,
      outputTokens: 1_512,
      models: [
        { modelFamily: 'gpt-4o', count: 2, percentage: 66.7 },
        { modelFamily: 'gpt-4o-mini', count: 1, percentage: 33.3 },
      ],
      p95DeltaMs: -1_380,
      p95DeltaPercent: -36.1,
      totalTokensDelta: -330,
      totalTokensDeltaPercent: -5.2,
    }),
    phase({
      profile: 'router-advanced',
      label: 'Router advanced',
      kind: 'router',
      routeStrategy: 'cost',
      runId: 'run-advanced',
      retries: 1,
      p50LatencyMs: 1_080,
      p95LatencyMs: 3_100,
      outputTokens: 1_212,
      models: [
        { modelFamily: 'gpt-4o', count: 1, percentage: 33.3 },
        { modelFamily: 'gpt-4o-mini', count: 2, percentage: 66.7 },
      ],
      p95DeltaMs: -720,
      p95DeltaPercent: -18.8,
      totalTokensDelta: -630,
      totalTokensDeltaPercent: -9.9,
    }),
  ],
}

describe('CompareRun', () => {
  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('renders completed profiles as scan-aligned telemetry cards with fixed deltas', () => {
    render(<CompareRun config={config} providerMode="foundry" comparison={completedComparison} onExport={vi.fn()} />)

    expect(screen.getByRole('region', { name: 'Profile comparison' })).toHaveAttribute('aria-busy', 'false')
    expect(screen.getByText((_, element) => element?.classList.contains('comparison-call-budget') === true
      && element.textContent?.includes('9 planned') === true)).toBeInTheDocument()
    expect(screen.getByRole('article', { name: 'Fixed deployment: Completed' })).toBeInTheDocument()
    expect(screen.getByRole('article', { name: 'Router default: Completed' })).toBeInTheDocument()
    expect(screen.getByRole('article', { name: 'Router advanced: Completed' })).toBeInTheDocument()
    expect(screen.getAllByText('vs fixed')).toHaveLength(4)
    expect(screen.getAllByText('3 / 3 token samples')).toHaveLength(3)
    expect(screen.getAllByText('Requested / completed / failed')).toHaveLength(3)
    expect(screen.getAllByText('Total tokens')).toHaveLength(3)
    expect(screen.getByText('6,354')).toBeInTheDocument()
    expect(screen.getByText('gpt-4o-mini (33.3%)')).toBeInTheDocument()
    expect(screen.getByText(/never retained or included in the JSON export/i)).toBeInTheDocument()
  })

  it('marks running progress as busy and names queued and running states without color alone', () => {
    const running: ComparisonState = {
      ...completedComparison,
      status: 'running',
      currentProfile: 'router-default',
      providerInvocations: 4,
      completedAt: undefined,
      phases: [
        completedComparison.phases[0],
        phase({ profile: 'router-default', label: 'Router default', kind: 'router', status: 'running' }),
        phase({ profile: 'router-advanced', label: 'Router advanced', kind: 'router', status: 'queued', runId: undefined, completed: 0, tokenSamples: 0, models: [] }),
      ],
    }
    render(<CompareRun config={config} providerMode="foundry" comparison={running} onExport={vi.fn()} />)

    expect(screen.getByRole('region', { name: 'Profile comparison' })).toHaveAttribute('aria-busy', 'true')
    expect(screen.getByRole('list', { name: 'Comparison progress' }).querySelector('[aria-current="step"]')).toHaveTextContent('Router default')
    expect(screen.getByRole('article', { name: 'Router default: Running' })).toBeInTheDocument()
    expect(screen.getByRole('article', { name: 'Router advanced: Queued' })).toBeInTheDocument()
  })

  it('shows only an eight-character child run prefix while keeping the opaque id in data', () => {
    const fullRunId = '12345678-90ab-cdef-1234-567890abcdef'
    render(
      <CompareRun
        config={config}
        providerMode="foundry"
        comparison={{
          ...completedComparison,
          phases: [phase({ runId: fullRunId }), ...completedComparison.phases.slice(1)],
        }}
        onExport={vi.fn()}
      />,
    )

    expect(document.body).not.toHaveTextContent(fullRunId)
    expect(document.body.textContent?.match(/12345678…/g)).toHaveLength(2)
  })

  it.each([
    ['failed', /stopped after a profile failed/i],
    ['stopped', /stopped by request/i],
  ] as const)('explains a %s comparison while retaining partial telemetry', (status, message) => {
    render(
      <CompareRun
        config={config}
        providerMode="foundry"
        comparison={{ ...completedComparison, status }}
        onExport={vi.fn()}
      />,
    )

    expect(screen.getByText(message)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Export JSON' })).toBeEnabled()
  })

  it('shows an explicit unavailable state for Ollama without disabling single runs', () => {
    render(
      <CompareRun
        config={{ enabled: false, profiles: [], unavailableReason: 'Ollama exposes one local model profile.' }}
        providerMode="ollama"
        comparison={null}
        onExport={vi.fn()}
      />,
    )

    expect(screen.getByText('Comparison unavailable')).toBeInTheDocument()
    expect(screen.getByText('Ollama exposes one local model profile.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Export JSON' })).toBeDisabled()
  })

  it('turns machine unavailable reasons into provider-specific copy', () => {
    render(
      <CompareRun
        config={{ enabled: false, profiles: [], unavailableReason: 'provider_exposes_fewer_than_two_profiles' }}
        providerMode="ollama"
        comparison={null}
        onExport={vi.fn()}
      />,
    )

    expect(screen.getByText(/Ollama exposes one local fixed profile/i)).toBeInTheDocument()
    expect(document.body).not.toHaveTextContent('provider_exposes_fewer_than_two_profiles')
  })

  it('loads the safe GET representation before creating a client-only JSON download', async () => {
    const onExport = vi.fn().mockResolvedValue(completedComparison)
    const createObjectURL = vi.fn().mockReturnValue('blob:comparison-export')
    const revokeObjectURL = vi.fn()
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    vi.stubGlobal('URL', { createObjectURL, revokeObjectURL })
    render(<CompareRun config={config} providerMode="foundry" comparison={completedComparison} onExport={onExport} />)

    fireEvent.click(screen.getByRole('button', { name: 'Export JSON' }))

    await waitFor(() => expect(onExport).toHaveBeenCalledWith('comparison-20260717'))
    expect(createObjectURL).toHaveBeenCalledWith(expect.any(Blob))
    expect(click).toHaveBeenCalledTimes(1)
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:comparison-export')
  })
})
