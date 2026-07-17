import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  createRun,
  EMPTY_SNAPSHOT,
  getComparison,
  normalizeComparison,
  normalizeComparisonReceipt,
  normalizeConfig,
  normalizeSnapshot,
  preferNewerSnapshot,
  reduceStreamMessage,
  startComparison,
  stopComparison,
} from './api'

const backendSnapshot = {
  sessionId: 'session-01',
  generatedAt: '2026-07-16T09:41:05.230Z',
  freshness: { lastEventAt: '2026-07-16T09:41:03.030Z', ageMs: 2200 },
  activeRun: {
    runId: 'run-7f3k',
    workload: 'summarize',
    scenario: 'consumer_slowdown',
    traffic: 12,
    status: 'running',
    startedAt: '2026-07-16T09:41:00.000Z',
    completedRequests: 18,
  },
  kpis: {
    requests: 22,
    completed: 18,
    failed: 2,
    p95LatencyMs: 268,
    eventsPerSecond: 13.2,
    consumerLag: 12_400,
    duplicatesDropped: 3,
  },
  traces: [{
    traceId: 'trace-abc',
    runId: 'run-7f3k',
    workload: 'summarize',
    scenario: 'consumer_slowdown',
    status: 'completed',
    startedAt: '2026-07-16T09:41:01.000Z',
    completedAt: '2026-07-16T09:41:01.268Z',
    latencyMs: 268,
    inputChars: 420,
    outputChars: 180,
    providerAlias: 'simulated',
    promptHash: 'sha256:abc123',
    partition: 2,
    attempt: 1,
    modelProfile: 'router-balanced',
    routeStrategy: 'balanced',
    selectedRoute: 'general',
    modelFamily: 'general-purpose',
    inputTokens: 96,
    outputTokens: 44,
  }],
  routing: {
    profileId: 'router-balanced',
    strategy: 'balanced',
    totalRequests: 22,
    explanation: 'Balances latency, quality, and cost signals.',
    routes: [
      { id: 'fast', label: 'Fast', requests: 12, share: 0.545, p95LatencyMs: 164, successRate: 0.992 },
      { id: 'general', label: 'General', requests: 10, share: 0.455, p95LatencyMs: 268, successRate: 0.984 },
    ],
  },
  partitions: [{ partition: 2, produced: 30, consumed: 18, lag: 12, peakLag: 19, duplicatesDropped: 1, lastOffset: 834_198 }],
}

describe('backend contract normalization', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('maps config strings and defaults into UI options', () => {
    const config = normalizeConfig({
      mode: 'foundry',
      transport: 'kafka',
      cloudReady: true,
      maxTrafficPerRun: 10,
      modelAlias: 'foundry-deployment',
      workloads: ['chat', 'summarize'],
      scenarios: ['healthy', 'consumer_slowdown'],
      defaults: { workload: 'chat', scenario: 'healthy', traffic: 12 },
    })

    expect(config.workloads[1]).toEqual({ id: 'summarize', label: 'summarize' })
    expect(config.models[0]?.id).toBe('foundry-deployment')
    expect(config).toMatchObject({ mode: 'foundry', transport: 'kafka', cloudReady: true, maxTrafficPerRun: 10 })
    expect(config.defaults).toMatchObject({ workloadId: 'chat', scenarioId: 'healthy', requestRate: 12 })
  })

  it('normalizes fixed and router execution profiles from the final config contract', () => {
    const config = normalizeConfig({
      mode: 'foundry',
      models: [
        { id: 'fixed', label: 'Fixed deployment', strategy: 'fixed', router: false },
        { id: 'router-balanced', label: 'Model Router · Balanced', strategy: 'balanced', router: true },
      ],
      modelProfiles: [
        { id: 'fixed', label: 'Fixed deployment', strategy: 'fixed', router: false },
        { id: 'router-balanced', label: 'Model Router · Balanced', strategy: 'balanced', router: true },
      ],
      defaults: { modelId: 'router-balanced', modelProfile: 'router-balanced' },
    })

    expect(config.modelProfiles).toEqual([
      expect.objectContaining({ id: 'fixed', strategy: 'fixed' }),
      expect.objectContaining({ id: 'router-balanced', strategy: 'balanced', kind: 'router' }),
    ])
    expect(config.defaults).toMatchObject({ modelId: 'router-balanced', modelProfile: 'router-balanced' })
  })

  it('normalizes the bounded comparison config and safe profile metadata', () => {
    const config = normalizeConfig({
      mode: 'foundry',
      comparison: {
        enabled: true,
        profiles: [
          { id: 'fixed', label: 'Fixed deployment', kind: 'fixed' },
          { id: 'router-default', label: 'Router default', kind: 'router', routeStrategy: 'balanced' },
        ],
        maxTrafficPerProfile: 4,
        providerInvocationLimit: 12,
      },
    })

    expect(config.comparison).toEqual({
      enabled: true,
      profiles: [
        expect.objectContaining({ id: 'fixed', label: 'Fixed deployment', kind: 'fixed' }),
        expect.objectContaining({ id: 'router-default', kind: 'router', routeStrategy: 'balanced' }),
      ],
      maxTrafficPerProfile: 4,
      providerInvocationLimit: 12,
      unavailableReason: undefined,
    })
  })

  it('derives display KPIs and trace metadata from a snapshot', () => {
    const snapshot = normalizeSnapshot(backendSnapshot)
    const values = Object.fromEntries(snapshot.kpis.map((kpi) => [kpi.key, kpi.value]))

    expect(snapshot.run).toMatchObject({ id: 'run-7f3k', status: 'running', requestRate: 12 })
    expect(values['ai-success']).toBe(90)
    expect(values['error-rate']).toBe(10)
    expect(values['throughput']).toBe(13.2)
    expect(values['telemetry-age']).toBe(2.2)
    expect(values['duplicates-dropped']).toBe(3)
    expect(snapshot.streamRate).toBe(13.2)
    expect(snapshot.partitions[0]).toMatchObject({ partition: 2, endOffset: 834_198, produced: 30, consumed: 18, peakLag: 19, duplicatesDropped: 1 })
    expect(snapshot.traces[0]?.metadata).toMatchObject({ promptHash: 'sha256:abc123', inputChars: 420, outputChars: 180 })
    expect(snapshot.traces[0]?.metadata).toMatchObject({
      modelProfile: 'router-balanced',
      routeStrategy: 'balanced',
      selectedRoute: 'general',
      selectedModelFamily: 'general-purpose',
      inputTokens: 96,
      outputTokens: 44,
    })
    expect(snapshot.traces[0]?.services).toEqual(['Simulator'])
    expect(snapshot.traces[0]?.spans[0]?.service).toBe('Simulator')
    expect(snapshot.routing).toMatchObject({
      strategy: 'balanced',
      requestsRouted: 22,
      routes: [
        { id: 'fast', requests: 12, share: 54.5, p95LatencyMs: 164, successRate: 99.2 },
        { id: 'general', requests: 10, share: 45.5, p95LatencyMs: 268, successRate: 98.4 },
      ],
    })
  })

  it('sends the selected execution profile under both compatibility fields', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response('{}', {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }))
    vi.stubGlobal('fetch', fetchMock)

    await createRun({
      workloadId: 'summarize',
      scenarioId: 'healthy',
      modelProfile: 'router-balanced',
      requestRate: 8,
    })

    const request = fetchMock.mock.calls[0]?.[1] as RequestInit
    expect(JSON.parse(String(request.body))).toMatchObject({
      workload: 'summarize',
      scenario: 'healthy',
      traffic: 8,
      modelProfile: 'router-balanced',
      modelId: 'router-balanced',
    })
  })

  it('clears an active run when the backend sends an explicit null', () => {
    const running = normalizeSnapshot(backendSnapshot)
    const completed = normalizeSnapshot({ ...backendSnapshot, activeRun: null }, running)

    expect(running.run?.status).toBe('running')
    expect(completed.run).toBeNull()
  })

  it('retains lastRun and normalizes a full comparison without sensitive payload fields', () => {
    const snapshot = normalizeSnapshot({
      lastRun: { runId: 'run-previous', status: 'completed', workload: 'chat' },
      comparison: {
        comparisonId: 'comparison-1',
        status: 'completed',
        workload: 'chat',
        scenario: 'healthy',
        trafficPerProfile: 2,
        profiles: ['fixed', 'router-default'],
        plannedRequests: 4,
        providerInvocations: 4,
        providerInvocationLimit: 6,
        prompt: 'must-not-export',
        responseBody: 'must-not-export-either',
        phases: [{
          profile: 'fixed',
          label: 'Fixed deployment',
          kind: 'fixed',
          status: 'completed',
          runId: 'run-fixed',
          requested: 2,
          completed: 2,
          failed: 0,
          retries: 0,
          successRate: 1,
          p50LatencyMs: 100,
          p95LatencyMs: 180,
          inputTokens: 20,
          outputTokens: 10,
          tokenSamples: 2,
          models: [{ modelFamily: 'gpt-4o', count: 2, percentage: 1 }],
          promptBody: 'also-private',
        }],
      },
    })

    expect(snapshot.lastRun).toMatchObject({ id: 'run-previous', status: 'completed' })
    expect(snapshot.comparison).toMatchObject({
      comparisonId: 'comparison-1',
      status: 'completed',
      profiles: ['fixed', 'router-default'],
      phases: [{ successRate: 1, models: [{ modelFamily: 'gpt-4o', percentage: 1 }] }],
    })
    expect(JSON.stringify(snapshot.comparison)).not.toContain('must-not-export')
    expect(JSON.stringify(snapshot.comparison)).not.toContain('also-private')
  })

  it('clears a comparison only when the snapshot explicitly sends null', () => {
    const comparison = normalizeComparison({
      comparisonId: 'comparison-1',
      status: 'running',
      workload: 'chat',
      trafficPerProfile: 2,
      profiles: ['fixed'],
      plannedRequests: 2,
      providerInvocations: 1,
      providerInvocationLimit: 3,
      phases: [],
    })
    const running = normalizeSnapshot({ comparison })
    const unchanged = normalizeSnapshot({ capturedAt: '2026-07-17T00:00:00Z' }, running)
    const cleared = normalizeSnapshot({ comparison: null }, running)

    expect(unchanged.comparison?.comparisonId).toBe('comparison-1')
    expect(cleared.comparison).toBeNull()
  })

  it('clears the current profile when a comparison becomes terminal', () => {
    const running = normalizeComparison({
      comparisonId: 'comparison-terminal',
      status: 'running',
      currentProfile: 'router-advanced',
      workload: 'chat',
      trafficPerProfile: 1,
      profiles: ['fixed', 'router-default', 'router-advanced'],
      plannedRequests: 3,
      providerInvocations: 3,
      providerInvocationLimit: 10,
      phases: [],
    })
    const completed = normalizeComparison({
      comparisonId: 'comparison-terminal',
      status: 'completed',
      currentProfile: null,
    }, running)

    expect(completed?.status).toBe('completed')
    expect(completed?.currentProfile).toBeUndefined()
  })

  it('does not let a late REST snapshot regress newer SSE comparison progress', () => {
    const completed = normalizeSnapshot({
      sessionId: 'session-compare',
      generatedAt: '2026-07-17T01:00:05Z',
      comparison: {
        comparisonId: 'comparison-1',
        status: 'completed',
        workload: 'chat',
        trafficPerProfile: 1,
        profiles: ['fixed', 'router-default', 'router-advanced'],
        plannedRequests: 3,
        providerInvocations: 3,
        providerInvocationLimit: 10,
        phases: [],
      },
    })
    const stale = normalizeSnapshot({
      sessionId: 'session-compare',
      generatedAt: '2026-07-17T01:00:04Z',
      comparison: {
        comparisonId: 'comparison-1',
        status: 'running',
        workload: 'chat',
        trafficPerProfile: 1,
        profiles: ['fixed', 'router-default', 'router-advanced'],
        plannedRequests: 3,
        providerInvocations: 2,
        providerInvocationLimit: 10,
        phases: [],
      },
    })

    expect(preferNewerSnapshot(completed, stale)).toBe(completed)
    expect(preferNewerSnapshot(completed, {
      ...stale,
      capturedAt: '2026-07-17T01:00:06Z',
    }).comparison?.status).toBe('completed')
  })

  it('normalizes direct comparison receipts', () => {
    expect(normalizeComparisonReceipt({
      comparisonId: 'comparison-2',
      status: 'queued',
      profiles: ['fixed', 'router-default', 'router-advanced'],
      trafficPerProfile: 3,
      plannedRequests: 9,
      providerInvocationLimit: 10,
    })).toEqual({
      comparisonId: 'comparison-2',
      status: 'queued',
      profiles: ['fixed', 'router-default', 'router-advanced'],
      trafficPerProfile: 3,
      plannedRequests: 9,
      providerInvocationLimit: 10,
    })
  })

  it('uses one comparison POST and typed stop/GET endpoints', async () => {
    const fullComparison = {
      comparisonId: 'comparison-3',
      status: 'completed',
      workload: 'chat',
      scenario: 'healthy',
      trafficPerProfile: 2,
      profiles: ['fixed'],
      plannedRequests: 2,
      providerInvocations: 2,
      providerInvocationLimit: 3,
      phases: [],
    }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({
        comparisonId: 'comparison-3',
        status: 'queued',
        profiles: ['fixed'],
        trafficPerProfile: 2,
        plannedRequests: 2,
        providerInvocationLimit: 3,
      }), { status: 202, headers: { 'Content-Type': 'application/json' } }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ comparisonId: 'comparison-3', status: 'stopped' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ ...fullComparison, prompt: 'private' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }))
    vi.stubGlobal('fetch', fetchMock)

    const receipt = await startComparison({ workload: 'chat', scenario: 'healthy', traffic: 2, prompt: 'same input' })
    const stopped = await stopComparison('comparison-3')
    const exported = await getComparison('comparison-3')

    expect(receipt).toMatchObject({ comparisonId: 'comparison-3', plannedRequests: 2 })
    expect(stopped).toMatchObject({ comparisonId: 'comparison-3', status: 'stopped' })
    expect(exported).toMatchObject({ comparisonId: 'comparison-3', status: 'completed' })
    expect(JSON.stringify(exported)).not.toContain('private')
    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/v1/comparisons')
    expect(JSON.parse(String((fetchMock.mock.calls[0]?.[1] as RequestInit).body))).toEqual({
      workload: 'chat',
      scenario: 'healthy',
      traffic: 2,
      prompt: 'same input',
    })
    expect(fetchMock.mock.calls[1]?.[0]).toBe('/api/v1/comparisons/comparison-3/stop')
    expect(fetchMock.mock.calls[2]?.[0]).toBe('/api/v1/comparisons/comparison-3')
  })

  it('does not present retrying or stopped traces as successful', () => {
    const retrying = normalizeSnapshot({
      ...backendSnapshot,
      traces: [{ ...backendSnapshot.traces[0], status: 'retrying' }],
    })
    const stopped = normalizeSnapshot({
      ...backendSnapshot,
      traces: [{ ...backendSnapshot.traces[0], status: 'stopped' }],
    })

    expect(retrying.traces[0]?.status).toBe('warning')
    expect(stopped.traces[0]?.status).toBe('warning')
  })

  it('turns telemetry envelopes into safe live events', () => {
    const next = reduceStreamMessage(EMPTY_SNAPSHOT, {
      schema_version: '1.0',
      event_id: 'evt-1',
      event_type: 'ai.request.completed',
      emitted_at: '2026-07-16T09:41:05.230Z',
      trace_id: 'trace-abc',
      workload: 'summarize',
      scenario: 'healthy',
      details: {
        prompt_hash: 'sha256:abc123',
        prompt_chars: 420,
        response_hash: 'sha256:def456',
        response_chars: 180,
        latency_ms: 268,
      },
    }, 'telemetry')

    expect(next.events).toHaveLength(1)
    expect(next.events[0]).toMatchObject({ id: 'evt-1', traceId: 'trace-abc', level: 'success' })
    expect(next.events[0]?.detail).toContain('268ms')
    expect(JSON.stringify(next.events[0])).not.toContain('prompt_hash')
    expect(JSON.stringify(next.events[0])).not.toContain('response_hash')
  })
})
