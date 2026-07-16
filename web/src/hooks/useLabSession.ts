import { useCallback, useEffect, useRef, useState } from 'react'
import {
  createRun,
  deleteSession,
  EMPTY_SNAPSHOT,
  fetchConfig,
  fetchSnapshot,
  normalizeSnapshot,
  reduceStreamMessage,
  stopRun,
  subscribeToStream,
} from '../lib/api'
import type { ConnectionState, LabConfig, LabSnapshot, RunInput } from '../types'

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
  pendingAction: 'run' | 'stop' | 'reset' | null
  start: (input: RunInput) => Promise<void>
  stop: () => Promise<void>
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
        setSnapshot((current) => reduceStreamMessage(current, payload, eventType))
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
        snapshotResult.status === 'fulfilled' ? snapshotResult.value : current,
        configResult.value,
      ))
    } else if (snapshotResult.status === 'fulfilled') {
      setSnapshot(snapshotResult.value)
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
    reset,
    refresh,
    toggleStream,
    dismissError: () => setError(null),
    clearEvents,
  }
}
