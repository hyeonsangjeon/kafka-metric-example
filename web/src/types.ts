export type ConnectionState = 'connecting' | 'connected' | 'disconnected'
export type HealthState = 'healthy' | 'degraded' | 'offline' | 'unknown'
export type EventLevel = 'success' | 'warning' | 'error' | 'info'
export type RunStatus = 'idle' | 'starting' | 'running' | 'stopping' | 'completed' | 'failed'
export type ComparisonStatus = 'queued' | 'running' | 'completed' | 'failed' | 'stopped' | 'unavailable'

export interface SelectOption {
  id: string
  label: string
  description?: string
}

export interface ModelProfileOption extends SelectOption {
  strategy?: string
  kind?: 'fixed' | 'router' | 'local' | 'unknown'
  available?: boolean
}

export interface ComparisonProfileOption extends SelectOption {
  kind?: 'fixed' | 'router' | 'local' | 'unknown'
  routeStrategy?: string
}

export interface ComparisonConfig {
  enabled: boolean
  profiles: ComparisonProfileOption[]
  maxTrafficPerProfile?: number
  providerInvocationLimit?: number
  unavailableReason?: string
}

export interface LabConfig {
  mode: 'simulated' | 'ollama' | 'foundry' | 'unknown'
  transport: 'memory' | 'kafka' | 'unknown'
  cloudReady: boolean
  maxTrafficPerRun?: number
  comparison?: ComparisonConfig
  workloads: SelectOption[]
  scenarios: SelectOption[]
  models: SelectOption[]
  modelProfiles: ModelProfileOption[]
  defaults: {
    workloadId?: string
    scenarioId?: string
    modelId?: string
    modelProfile?: string
    requestRate?: number
  }
}

export interface RunState {
  id: string
  status: RunStatus
  workloadId?: string
  workloadLabel?: string
  scenarioId?: string
  scenarioLabel?: string
  startedAt?: string
  durationSeconds?: number
  requestRate?: number
  modelProfile?: string
  modelProfileLabel?: string
}

export interface ComparisonModelMix {
  modelFamily: string
  count: number
  percentage: number
}

export interface ComparisonPhase {
  profile: string
  label: string
  kind: 'fixed' | 'router' | 'local' | 'unknown'
  routeStrategy?: string
  status: ComparisonStatus
  runId?: string
  requested: number
  completed: number
  failed: number
  retries: number
  successRate: number | null
  p50LatencyMs: number | null
  p95LatencyMs: number | null
  inputTokens: number
  outputTokens: number
  tokenSamples: number
  models: ComparisonModelMix[]
  p95DeltaMs: number | null
  p95DeltaPercent: number | null
  totalTokensDelta: number | null
  totalTokensDeltaPercent: number | null
}

export interface ComparisonState {
  comparisonId: string
  status: ComparisonStatus
  workload: string
  scenario?: string
  trafficPerProfile: number
  profiles: string[]
  currentProfile?: string
  plannedRequests: number
  providerInvocations: number
  providerInvocationLimit: number
  startedAt?: string
  completedAt?: string
  phases: ComparisonPhase[]
}

export interface ComparisonReceipt {
  comparisonId: string
  status: ComparisonStatus
  profiles: string[]
  trafficPerProfile?: number
  plannedRequests?: number
  providerInvocationLimit?: number
}

export interface KpiValue {
  key: string
  label: string
  value: number | null
  unit?: string
  tone: 'good' | 'warn' | 'bad' | 'accent' | 'neutral'
  series: number[]
  change?: number
}

export interface PartitionTelemetry {
  id: string
  name: string
  topic: string
  partition: number | null
  lag: number | null
  endOffset: number | null
  produced: number | null
  consumed: number | null
  peakLag: number | null
  duplicatesDropped: number | null
  blocks: Array<'idle' | 'active' | 'warning' | 'error' | 'success'>
}

export interface LabEvent {
  id: string
  timestamp: string
  title: string
  detail?: string
  level: EventLevel
  category?: string
  traceId?: string
}

export interface TraceSpan {
  id: string
  name: string
  service: string
  startedAtMs: number
  durationMs: number
  status: 'ok' | 'error' | 'warning'
  attributes?: Record<string, string | number | boolean>
}

export interface TraceSummary {
  id: string
  startedAt: string
  durationMs: number
  status: 'ok' | 'error' | 'warning'
  workload?: string
  scenario?: string
  services: string[]
  spans: TraceSpan[]
  metadata?: {
    runId?: string
    promptHash?: string
    partition?: number
    attempt?: number
    inputChars?: number
    outputChars?: number
    inputTokens?: number
    outputTokens?: number
    errorCode?: string
    modelProfile?: string
    routeStrategy?: string
    selectedRoute?: string
    selectedModelFamily?: string
  }
}

export interface RoutingRoute {
  id: string
  label: string
  requests: number
  share: number
  p95LatencyMs: number | null
  successRate: number | null
}

export interface RoutingDecision {
  strategy: string
  requestsRouted: number
  explanation?: string
  routes: RoutingRoute[]
}

export interface SystemNode {
  id: string
  name: string
  detail: string
  kind: 'foundry' | 'gateway' | 'kafka' | 'consumer' | 'browser'
  health: HealthState
  metric?: string
}

export interface LabSnapshot {
  sessionId?: string
  capturedAt?: string
  kafkaHealth: HealthState
  foundryHealth: HealthState
  streamRate: number | null
  run: RunState | null
  lastRun: RunState | null
  comparison: ComparisonState | null
  kpis: KpiValue[]
  partitions: PartitionTelemetry[]
  events: LabEvent[]
  traces: TraceSummary[]
  system: SystemNode[]
  routing: RoutingDecision | null
}

export interface RunInput {
  workloadId: string
  scenarioId?: string
  modelId?: string
  modelProfile?: string
  durationSeconds?: number
  requestRate?: number
  prompt?: string
}

export interface ComparisonInput {
  workload: string
  scenario?: string
  traffic: number
  prompt?: string
}

export interface StreamEnvelope {
  type?: string
  data?: unknown
  payload?: unknown
  [key: string]: unknown
}

export type AppView = 'live' | 'traces' | 'system'
