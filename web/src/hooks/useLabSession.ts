import { useCallback, useEffect, useRef, useState } from 'react'
import {
  createRun,
  deleteSession,
  EMPTY_SNAPSHOT,
  fetchConfig,
  fetchSnapshot,
  getComparison,
  normalizeComparison,
  normalizeSnapshot,
  preferNewerSnapshot,
  reduceStreamMessage,
  startComparison as requestComparison,
  stopComparison as requestComparisonStop,
  stopRun,
  subscribeToStream,
} from '../lib/api'
import type {
  ComparisonInput,
  ComparisonState,
  ConnectionState,
  LabConfig,
  LabSnapshot,
  RunInput,
} from '../types'

const EMPTY_CONFIG: LabConfig = {
  mode: 'unknown',
  transport: 'unknown',
  cloudReady: false,
  workloads: [],
  scenarios: [],
  models: [],
  modelProfiles: [],
  defaults: {},
}

function withServiceReadiness(snapshot: LabSnapshot, config: LabConfig): LabSnapshot {
  return {
    ...snapshot,
    kafkaHealth: snapshot.kafkaHealth !== 'unknown'
      ? snapshot.kafkaHealth
      : config.transport === 'unknown' ? 'unknown' : 'healthy',
    foundryHealth: snapshot.foundryHealth !== 'unknown'
      ? snapshot.foundryHealth
      : config.mode === 'unknown'
      ? snapshot.foundryHealth
      : config.mode === 'simulated' || config.cloudReady
        ? 'healthy'
        : 'degraded',
  }
}

export interface LabSessionState {
  config: LabConfig
  snapshot: LabSnapshot
  connection: ConnectionState
  error: string | null
  streamPaused: boolean
  pendingAction: 'run' | 'stop' | 'compare' | 'stop-comparison' | 'reset' | null
  start: (input: RunInput) => Promise<void>
  stop: () => Promise<void>
  compare: (input: ComparisonInput) => Promise<void>
  stopComparison: () => Promise<void>
  loadComparison: (comparisonId: string) => Promise<ComparisonState>
  reset: () => Promise<void>
  refresh: () => Promise<void>
  toggleStream: () => void
  dismissError: () => void
  clearEvents: () => void
}

function errorMessage(error: unknown): string {
  if (error instanceof Error && error.message) return error.message
  return 'The lab service is unavailable.'
}

export function useLabSession(): LabSessionState {
  const [config, setConfig] = useState<LabConfig>(EMPTY_CONFIG)
  const [snapshot, setSnapshot] = useState<LabSnapshot>(EMPTY_SNAPSHOT)
  const [connection, setConnection] = useState<ConnectionState>('connecting')
  const [error, setError] = useState<string | null>(null)
  const [streamPaused, setStreamPaused] = useState(false)
  const [pendingAction, setPendingAction] = useState<LabSessionState['pendingAction']>(null)
  const streamCleanup = useRef<(() => void) | null>(null)

  const openStream = useCallback(() => {
    streamCleanup.current?.()
    setConnection('connecting')
    streamCleanup.current = subscribeToStream({
      onOpen: () => {
        setConnection('connected')
        setError(null)
      },
      onMessage: (payload, eventType) => {
        setSnapshot((current) => preferNewerSnapshot(
          current,
          reduceStreamMessage(current, payload, eventType),
        ))
      },
      onError: () => setConnection('disconnected'),
    })
  }, [])

  const refresh = useCallback(async () => {
    setConnection((current) => current === 'connected' ? current : 'connecting')
    const [configResult, snapshotResult] = await Promise.allSettled([fetchConfig(), fetchSnapshot()])
    if (configResult.status === 'fulfilled') {
      setConfig(configResult.value)
      setSnapshot((current) => withServiceReadiness(
        snapshotResult.status === 'fulfilled'
          ? preferNewerSnapshot(current, snapshotResult.value)
          : current,
        configResult.value,
      ))
    } else if (snapshotResult.status === 'fulfilled') {
      setSnapshot((current) => preferNewerSnapshot(current, snapshotResult.value))
    }
    if (configResult.status === 'rejected' && snapshotResult.status === 'rejected') {
      setConnection('disconnected')
      setError(errorMessage(snapshotResult.reason))
      throw snapshotResult.reason
    }
    setError(null)
  }, [])

  useEffect(() => {
    const startTimer = window.setTimeout(() => {
      void refresh().catch(() => undefined)
      openStream()
    }, 0)
    return () => {
      window.clearTimeout(startTimer)
      streamCleanup.current?.()
      streamCleanup.current = null
    }
  }, [openStream, refresh])

  const start = useCallback(async (input: RunInput) => {
    setPendingAction('run')
    setError(null)
    try {
      const response = await createRun(input)
      setSnapshot((current) => normalizeSnapshot(response, current))
      await refresh()
    } catch (caught) {
      setError(errorMessage(caught))
      throw caught
    } finally {
      setPendingAction(null)
    }
  }, [refresh])

  const stop = useCallback(async () => {
    const runId = snapshot.run?.id
    if (!runId) return
    setPendingAction('stop')
    setError(null)
    try {
      const response = await stopRun(runId)
      setSnapshot((current) => normalizeSnapshot(response, current))
      await refresh()
    } catch (caught) {
      setError(errorMessage(caught))
      throw caught
    } finally {
      setPendingAction(null)
    }
  }, [refresh, snapshot.run?.id])

  const compare = useCallback(async (input: ComparisonInput) => {
    setPendingAction('compare')
    setError(null)
    try {
      const receipt = await requestComparison(input)
      const profileOptions = new Map(config.comparison?.profiles.map((profile) => [profile.id, profile]))
      setSnapshot((current) => ({
        ...current,
        comparison: {
          comparisonId: receipt.comparisonId,
          status: receipt.status,
          workload: input.workload,
          scenario: input.scenario,
          trafficPerProfile: receipt.trafficPerProfile ?? input.traffic,
          profiles: receipt.profiles,
          plannedRequests: receipt.plannedRequests ?? receipt.profiles.length * input.traffic,
          providerInvocations: 0,
          providerInvocationLimit: receipt.providerInvocationLimit ?? config.comparison?.providerInvocationLimit ?? 0,
          phases: receipt.profiles.map((profile) => {
            const option = profileOptions.get(profile)
            return {
              profile,
              label: option?.label ?? profile,
              kind: option?.kind ?? 'unknown',
              routeStrategy: option?.routeStrategy,
              status: 'queued',
              requested: input.traffic,
              completed: 0,
              failed: 0,
              retries: 0,
              successRate: null,
              p50LatencyMs: null,
              p95LatencyMs: null,
              inputTokens: 0,
              outputTokens: 0,
              tokenSamples: 0,
              models: [],
              p95DeltaMs: null,
              p95DeltaPercent: null,
              totalTokensDelta: null,
              totalTokensDeltaPercent: null,
            }
          }),
        },
      }))
      await refresh()
    } catch (caught) {
      setError(errorMessage(caught))
      throw caught
    } finally {
      setPendingAction(null)
    }
  }, [config.comparison, refresh])

  const stopComparison = useCallback(async () => {
    const comparisonId = snapshot.comparison?.comparisonId
    if (!comparisonId) return
    setPendingAction('stop-comparison')
    setError(null)
    try {
      const receipt = await requestComparisonStop(comparisonId)
      setSnapshot((current) => ({
        ...current,
        comparison: normalizeComparison(receipt, current.comparison),
      }))
      await refresh()
    } catch (caught) {
      setError(errorMessage(caught))
      throw caught
    } finally {
      setPendingAction(null)
    }
  }, [refresh, snapshot.comparison?.comparisonId])

  const loadComparison = useCallback((comparisonId: string) => getComparison(comparisonId), [])

  const reset = useCallback(async () => {
    setPendingAction('reset')
    setError(null)
    try {
      await deleteSession()
      setSnapshot(EMPTY_SNAPSHOT)
      await refresh()
      if (!streamPaused) openStream()
    } catch (caught) {
      setError(errorMessage(caught))
      throw caught
    } finally {
      setPendingAction(null)
    }
  }, [openStream, refresh, streamPaused])

  const toggleStream = useCallback(() => {
    setStreamPaused((paused) => {
      if (paused) {
        openStream()
      } else {
        streamCleanup.current?.()
        streamCleanup.current = null
      }
      return !paused
    })
  }, [openStream])

  const clearEvents = useCallback(() => {
    setSnapshot((current) => ({ ...current, events: [] }))
  }, [])

  return {
    config,
    snapshot,
    connection,
    error,
    streamPaused,
    pendingAction,
    start,
    stop,
    compare,
    stopComparison,
    loadComparison,
    reset,
    refresh,
    toggleStream,
    dismissError: () => setError(null),
    clearEvents,
  }
}
