import type {
  EventLevel,
  HealthState,
  KpiValue,
  LabConfig,
  LabEvent,
  LabSnapshot,
  ModelProfileOption,
  PartitionTelemetry,
  RoutingDecision,
  RoutingRoute,
  RunInput,
  RunState,
  StreamEnvelope,
  SystemNode,
  TraceSpan,
  TraceSummary,
} from '../types'

const API_BASE = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '')

type UnknownRecord = Record<string, unknown>

const EMPTY_SERIES: number[] = []

export const EMPTY_SNAPSHOT: LabSnapshot = {
  kafkaHealth: 'unknown',
  foundryHealth: 'unknown',
  streamRate: null,
  run: null,
  kpis: [
    { key: 'ai-success', label: 'AI success', value: null, unit: '%', tone: 'good', series: EMPTY_SERIES },
    { key: 'p95-latency', label: 'P95 latency', value: null, unit: 'ms', tone: 'good', series: EMPTY_SERIES },
    { key: 'throughput', label: 'Throughput', value: null, unit: 'rps', tone: 'accent', series: EMPTY_SERIES },
    { key: 'consumer-lag', label: 'Consumer lag', value: null, tone: 'warn', series: EMPTY_SERIES },
    { key: 'telemetry-age', label: 'Telemetry age', value: null, unit: 's', tone: 'warn', series: EMPTY_SERIES },
    { key: 'duplicates-dropped', label: 'Duplicates dropped', value: null, tone: 'accent', series: EMPTY_SERIES },
    { key: 'error-rate', label: 'Error rate', value: null, unit: '%', tone: 'bad', series: EMPTY_SERIES },
  ],
  partitions: [],
  events: [],
  traces: [],
  system: [],
  routing: null,
}

function record(value: unknown): UnknownRecord | undefined {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? (value as UnknownRecord)
    : undefined
}

function array(value: unknown): unknown[] {
  return Array.isArray(value) ? value : []
}

function first(source: UnknownRecord | undefined, keys: string[]): unknown {
  if (!source) return undefined
  for (const key of keys) {
    if (source[key] !== undefined && source[key] !== null) return source[key]
  }
  return undefined
}

function textValue(source: UnknownRecord | undefined, keys: string[], fallback = ''): string {
  const value = first(source, keys)
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  return fallback
}

function numberValue(source: UnknownRecord | undefined, keys: string[], fallback: number | null = null): number | null {
  const value = first(source, keys)
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (typeof value === 'string' && value.trim() !== '') {
    const parsed = Number(value)
    if (Number.isFinite(parsed)) return parsed
  }
  return fallback
}

function normalizeHealth(value: unknown): HealthState {
  const normalized = String(value ?? '').toLowerCase()
  if (['healthy', 'up', 'ok', 'connected', 'ready'].includes(normalized)) return 'healthy'
  if (['degraded', 'warning', 'warn', 'slow'].includes(normalized)) return 'degraded'
  if (['offline', 'down', 'failed', 'error', 'disconnected'].includes(normalized)) return 'offline'
  return 'unknown'
}

function normalizeEventLevel(value: unknown): EventLevel {
  const normalized = String(value ?? '').toLowerCase()
  if (['success', 'ok', 'completed', 'healthy'].includes(normalized)) return 'success'
  if (['warn', 'warning', 'degraded', 'running', 'queued', 'retrying', 'stopped', 'cancelled', 'throttled'].includes(normalized)) return 'warning'
  if (['error', 'failed', 'failure', 'critical'].includes(normalized)) return 'error'
  return 'info'
}

function normalizeRunStatus(value: unknown): RunState['status'] {
  const normalized = String(value ?? '').toLowerCase()
  if (['starting', 'queued'].includes(normalized)) return 'starting'
  if (['running', 'active'].includes(normalized)) return 'running'
  if (['stopping', 'cancelling'].includes(normalized)) return 'stopping'
  if (['completed', 'complete', 'done', 'stopped'].includes(normalized)) return 'completed'
  if (['failed', 'error'].includes(normalized)) return 'failed'
  return 'idle'
}

function normalizeOption(value: unknown, index: number) {
  if (typeof value === 'string') return { id: value, label: value }
  const item = record(value)
  const id = textValue(item, ['id', 'value', 'key', 'name'], `option-${index}`)
  return {
    id,
    label: textValue(item, ['label', 'displayName', 'display_name', 'name'], id),
    description: textValue(item, ['description', 'detail']) || undefined,
  }
}

function normalizeModelProfile(value: unknown, index: number): ModelProfileOption {
  const option = normalizeOption(value, index)
  const item = record(value)
  const rawKind = textValue(item, ['kind', 'type', 'profileKind', 'profile_kind']).toLowerCase()
  const isRouter = first(item, ['router', 'isRouter', 'is_router']) === true
  const kind: ModelProfileOption['kind'] = isRouter
    ? 'router'
    : ['fixed', 'router', 'local'].includes(rawKind)
    ? rawKind as ModelProfileOption['kind']
    : rawKind.includes('router')
      ? 'router'
      : rawKind
        ? 'unknown'
        : undefined
  return {
    ...option,
    strategy: textValue(item, ['strategy', 'routingStrategy', 'routing_strategy', 'profile']) || undefined,
    kind,
    available: first(item, ['available', 'enabled', 'ready']) === false ? false : undefined,
  }
}

export function normalizeConfig(input: unknown): LabConfig {
  const root = record(input)
  const source = record(first(root, ['config'])) ?? root
  const defaults = record(first(source, ['defaults', 'default']))
  const modelAlias = textValue(source, ['modelAlias', 'model_alias'])
  const modelInput = array(first(source, ['models', 'modelOptions', 'model_options']))
  const models = modelInput.map(normalizeOption)
  const profileInput = first(source, ['modelProfiles', 'model_profiles', 'executionProfiles', 'execution_profiles', 'profiles'])
  const modelProfiles = array(profileInput).map(normalizeModelProfile)
  const mode = textValue(source, ['mode']).toLowerCase()
  const transport = textValue(source, ['transport']).toLowerCase()
  return {
    mode: mode === 'simulated' || mode === 'ollama' || mode === 'foundry' ? mode : 'unknown',
    transport: transport === 'memory' || transport === 'kafka' ? transport : 'unknown',
    cloudReady: first(source, ['cloudReady', 'cloud_ready']) === true,
    maxTrafficPerRun: numberValue(source, ['maxTrafficPerRun', 'max_traffic_per_run']) ?? undefined,
    workloads: array(first(source, ['workloads', 'workloadOptions', 'workload_options'])).map(normalizeOption),
    scenarios: array(first(source, ['scenarios', 'failureScenarios', 'failure_scenarios'])).map(normalizeOption),
    models: models.length ? models : (modelAlias ? [{ id: modelAlias, label: modelAlias }] : []),
    modelProfiles: modelProfiles.length
      ? modelProfiles
      : (modelInput.length ? modelInput.map(normalizeModelProfile) : modelAlias ? [{ id: modelAlias, label: modelAlias, kind: 'fixed' }] : []),
    defaults: {
      workloadId: textValue(defaults, ['workloadId', 'workload_id', 'workload']) || undefined,
      scenarioId: textValue(defaults, ['scenarioId', 'scenario_id', 'scenario']) || undefined,
      modelId: textValue(defaults, ['modelId', 'model_id', 'model']) || undefined,
      modelProfile: textValue(defaults, ['modelProfile', 'model_profile', 'executionProfile', 'execution_profile']) || undefined,
      requestRate: numberValue(defaults, ['requestRate', 'request_rate', 'traffic']) ?? undefined,
    },
  }
}

export function normalizeRun(input: unknown): RunState | null {
  const source = record(input)
  if (!source) return null
  const id = textValue(source, ['id', 'runId', 'run_id'])
  const status = normalizeRunStatus(first(source, ['status', 'state']))
  if (!id && status === 'idle') return null
  return {
    id: id || 'current-run',
    status,
    workloadId: textValue(source, ['workloadId', 'workload_id']) || undefined,
    workloadLabel: textValue(source, ['workloadLabel', 'workload_label', 'workload']) || undefined,
    scenarioId: textValue(source, ['scenarioId', 'scenario_id']) || undefined,
    scenarioLabel: textValue(source, ['scenarioLabel', 'scenario_label', 'scenario']) || undefined,
    startedAt: textValue(source, ['startedAt', 'started_at', 'startTime', 'start_time']) || undefined,
    durationSeconds: numberValue(source, ['durationSeconds', 'duration_seconds', 'duration']) ?? undefined,
    requestRate: numberValue(source, ['requestRate', 'request_rate', 'rps', 'traffic']) ?? undefined,
    modelProfile: textValue(source, ['modelProfile', 'model_profile', 'executionProfile', 'execution_profile', 'modelId', 'model_id']) || undefined,
    modelProfileLabel: textValue(source, ['modelProfileLabel', 'model_profile_label', 'executionProfileLabel', 'execution_profile_label']) || undefined,
  }
}

const KPI_SPECS = [
  { key: 'ai-success', aliases: ['aiSuccessRate', 'ai_success_rate', 'successRate', 'success_rate'], label: 'AI success', unit: '%', tone: 'good' as const },
  { key: 'p95-latency', aliases: ['p95LatencyMs', 'p95_latency_ms', 'p95Latency', 'p95_latency'], label: 'P95 latency', unit: 'ms', tone: 'good' as const },
  { key: 'throughput', aliases: ['throughputRps', 'throughput_rps', 'throughput', 'requestsPerSecond', 'requests_per_second', 'eventsPerSecond', 'events_per_second'], label: 'Throughput', unit: 'ev/s', tone: 'accent' as const },
  { key: 'consumer-lag', aliases: ['consumerLag', 'consumer_lag', 'lag'], label: 'Consumer lag', unit: undefined, tone: 'warn' as const },
  { key: 'telemetry-age', aliases: ['telemetryAgeSeconds', 'telemetry_age_seconds', 'telemetryAge', 'telemetry_age'], label: 'Telemetry age', unit: 's', tone: 'warn' as const },
  { key: 'duplicates-dropped', aliases: ['duplicatesDropped', 'duplicates_dropped'], label: 'Duplicates dropped', unit: undefined, tone: 'accent' as const },
  { key: 'error-rate', aliases: ['errorRate', 'error_rate', 'failureRate', 'failure_rate'], label: 'Error rate', unit: '%', tone: 'bad' as const },
]

function normalizeKpis(input: unknown, previous: KpiValue[]): KpiValue[] {
  const source = record(input)
  const list = Array.isArray(input) ? input : undefined
  const completed = numberValue(source, ['completed'])
  const failed = numberValue(source, ['failed'])
  const resolvedTotal = completed != null && failed != null ? completed + failed : null
  const successRate = resolvedTotal && completed != null ? (completed / resolvedTotal) * 100 : null
  const errorRate = resolvedTotal && failed != null ? (failed / resolvedTotal) * 100 : null
  return KPI_SPECS.map((spec, index) => {
    const listMatch = list?.find((item) => {
      const entry = record(item)
      const key = textValue(entry, ['key', 'id', 'name'])
      return key === spec.key || spec.aliases.includes(key)
    })
    const derived = spec.key === 'ai-success' ? successRate : spec.key === 'error-rate' ? errorRate : undefined
    const raw = listMatch ?? first(source, [spec.key, ...spec.aliases]) ?? derived
    const rawRecord = record(raw)
    const previousItem = previous.find((item) => item.key === spec.key) ?? previous[index]
    const direct = typeof raw === 'number' ? raw : null
    const series = array(first(rawRecord, ['series', 'history', 'values']))
      .map((value) => typeof value === 'number' ? value : Number(value))
      .filter(Number.isFinite)
    const value = direct ?? numberValue(rawRecord, ['value', 'current'], previousItem?.value ?? null)
    const nextSeries = series.length
      ? series.slice(-30)
      : value != null && value !== previousItem?.value
        ? [...(previousItem?.series ?? []), value].slice(-30)
        : (previousItem?.series ?? [])
    return {
      key: spec.key,
      label: textValue(rawRecord, ['label', 'name'], spec.label),
      value,
      unit: textValue(rawRecord, ['unit'], spec.unit ?? '') || undefined,
      tone: spec.tone,
      series: nextSeries,
      change: numberValue(rawRecord, ['change', 'delta', 'changePercent', 'change_percent']) ?? undefined,
    }
  })
}

function deriveBlocks(lag: number | null, produced: number | null, count = 38): PartitionTelemetry['blocks'] {
  if (lag == null && produced == null) return Array.from({ length: count }, () => 'idle')
  const pressure = Math.max(0, Math.min(1, (lag ?? 0) / ((lag ?? 0) + 20)))
  const warningStart = Math.round(count * (1 - pressure * 0.75))
  const activeStart = Math.max(0, count - Math.min(count, produced ?? 0))
  return Array.from({ length: count }, (_, index) => {
    if ((lag ?? 0) > 0 && index >= warningStart) return 'warning'
    if (index >= activeStart) return 'active'
    return 'idle'
  })
}

export function normalizePartition(input: unknown, index = 0): PartitionTelemetry {
  const source = record(input)
  const partition = numberValue(source, ['partition', 'partitionId', 'partition_id'])
  const lag = numberValue(source, ['lag', 'consumerLag', 'consumer_lag'])
  const produced = numberValue(source, ['produced', 'producedRecords', 'produced_records'])
  const rawBlocks = array(first(source, ['blocks', 'segments', 'timeline']))
  const blocks = rawBlocks.map((value) => {
    const normalized = String(record(value)?.state ?? value).toLowerCase()
    if (normalized === 'active') return 'active'
    if (['warning', 'warn', 'lag'].includes(normalized)) return 'warning'
    if (['error', 'failed'].includes(normalized)) return 'error'
    if (['success', 'ok'].includes(normalized)) return 'success'
    return 'idle'
  }) satisfies PartitionTelemetry['blocks']
  const topic = textValue(source, ['topic', 'topicName', 'topic_name'], 'foundry.telemetry.v1')
  return {
    id: textValue(source, ['id'], `${topic}-${partition ?? index}`),
    name: textValue(source, ['name', 'label'], `Kafka / ${topic}`),
    topic,
    partition: partition ?? index,
    lag,
    endOffset: numberValue(source, ['endOffset', 'end_offset', 'offset', 'lastOffset', 'last_offset']),
    produced,
    consumed: numberValue(source, ['consumed', 'consumedRecords', 'consumed_records']),
    peakLag: numberValue(source, ['peakLag', 'peak_lag']),
    duplicatesDropped: numberValue(source, ['duplicatesDropped', 'duplicates_dropped']),
    blocks: blocks.length ? blocks : deriveBlocks(lag, produced),
  }
}

export function normalizeEvent(input: unknown, index = 0): LabEvent {
  const source = record(input)
  const details = record(first(source, ['details']))
  const timestamp = textValue(source, ['timestamp', 'time', 'createdAt', 'created_at', 'emitted_at'], new Date().toISOString())
  const eventType = textValue(source, ['event_type', 'eventType', 'type'])
  const errorCode = textValue(details, ['error_code', 'errorCode'])
  const inferredLevel = errorCode || /failed|error/i.test(eventType)
    ? 'error'
    : /throttl|slow|lag|retry/i.test(eventType)
      ? 'warning'
      : /complete|success|delivered/i.test(eventType)
        ? 'success'
        : 'info'
  const level = first(source, ['level', 'severity', 'status']) !== undefined
    ? normalizeEventLevel(first(source, ['level', 'severity', 'status']))
    : inferredLevel
  const humanTitle = eventType
    ? eventType.replace(/[._-]+/g, ' ').replace(/\b\w/g, (letter) => letter.toUpperCase())
    : 'Telemetry event'
  const workload = textValue(source, ['workload'])
  const scenario = textValue(source, ['scenario'])
  const latency = numberValue(details, ['latency_ms', 'latencyMs'])
  const detailParts = [workload, scenario !== 'healthy' ? scenario.replaceAll('_', ' ') : '', latency != null ? `${Math.round(latency)}ms` : '', errorCode]
    .filter(Boolean)
  return {
    id: textValue(source, ['id', 'eventId', 'event_id'], `event-${timestamp}-${index}`),
    timestamp,
    title: textValue(source, ['title', 'message', 'name', 'event'], humanTitle),
    detail: textValue(source, ['detail', 'description', 'summary']) || detailParts.join(' · ') || undefined,
    level,
    category: textValue(source, ['category', 'kind', 'provider_alias']) || eventType.split(/[._-]/)[0] || undefined,
    traceId: textValue(source, ['traceId', 'trace_id']) || undefined,
  }
}

function normalizeSpan(input: unknown, index: number): TraceSpan {
  const source = record(input)
  const attributes = record(first(source, ['attributes', 'tags']))
  const status = normalizeEventLevel(first(source, ['status', 'level']))
  return {
    id: textValue(source, ['id', 'spanId', 'span_id'], `span-${index}`),
    name: textValue(source, ['name', 'operation', 'operationName', 'operation_name'], 'span'),
    service: textValue(source, ['service', 'serviceName', 'service_name'], 'unknown'),
    startedAtMs: numberValue(source, ['startedAtMs', 'started_at_ms', 'startMs', 'start_ms', 'offsetMs', 'offset_ms'], 0) ?? 0,
    durationMs: numberValue(source, ['durationMs', 'duration_ms', 'duration'], 0) ?? 0,
    status: status === 'error' ? 'error' : status === 'warning' ? 'warning' : 'ok',
    attributes: attributes as TraceSpan['attributes'],
  }
}

export function normalizeTrace(input: unknown, index = 0): TraceSummary {
  const source = record(input)
  const spans = array(first(source, ['spans', 'timeline'])).map(normalizeSpan)
  const status = normalizeEventLevel(first(source, ['status', 'level']))
  const providerAlias = textValue(source, ['providerAlias', 'provider_alias'])
  const providerService = providerAlias.toLowerCase().includes('simulated')
    ? 'Simulator'
    : providerAlias.toLowerCase().includes('ollama')
      ? 'Ollama'
      : 'Foundry'
  const services = array(first(source, ['services', 'serviceNames', 'service_names']))
    .map(String)
    .filter(Boolean)
  const startedAt = textValue(source, ['startedAt', 'started_at', 'timestamp', 'time'], new Date().toISOString())
  const latency = numberValue(source, ['durationMs', 'duration_ms', 'duration', 'latencyMs', 'latency_ms'], 0) ?? 0
  const traceSpans = spans.length ? spans : latency > 0 ? [{
    id: `${textValue(source, ['traceId', 'trace_id'], `trace-${index}`)}-provider`,
    name: 'AI request',
    service: providerService,
    startedAtMs: 0,
    durationMs: latency,
    status: status === 'error' ? 'error' as const : status === 'warning' ? 'warning' as const : 'ok' as const,
  }] : []
  return {
    id: textValue(source, ['id', 'traceId', 'trace_id'], `trace-${startedAt}-${index}`),
    startedAt,
    durationMs: latency,
    status: status === 'error' ? 'error' : status === 'warning' ? 'warning' : 'ok',
    workload: textValue(source, ['workload', 'workloadName', 'workload_name']) || undefined,
    scenario: textValue(source, ['scenario', 'scenarioName', 'scenario_name']) || undefined,
    services: services.length ? services : Array.from(new Set(traceSpans.map((span) => span.service))),
    spans: traceSpans,
    metadata: {
      runId: textValue(source, ['runId', 'run_id']) || undefined,
      promptHash: textValue(source, ['promptHash', 'prompt_hash']) || undefined,
      partition: numberValue(source, ['partition']) ?? undefined,
      attempt: numberValue(source, ['attempt']) ?? undefined,
      inputChars: numberValue(source, ['inputChars', 'input_chars']) ?? undefined,
      outputChars: numberValue(source, ['outputChars', 'output_chars']) ?? undefined,
      inputTokens: numberValue(source, ['inputTokens', 'input_tokens', 'promptTokens', 'prompt_tokens']) ?? undefined,
      outputTokens: numberValue(source, ['outputTokens', 'output_tokens', 'completionTokens', 'completion_tokens']) ?? undefined,
      errorCode: textValue(source, ['errorCode', 'error_code']) || undefined,
      modelProfile: textValue(source, ['modelProfile', 'model_profile', 'profileId', 'profile_id']) || undefined,
      routeStrategy: textValue(source, ['routingStrategy', 'routing_strategy', 'routeStrategy', 'route_strategy', 'strategy']) || undefined,
      selectedRoute: textValue(source, ['modelRouteLabel', 'model_route_label', 'selectedRoute', 'selected_route', 'routeLabel', 'route_label', 'modelRouteId', 'model_route_id']) || undefined,
      selectedModelFamily: textValue(source, ['selectedModelFamily', 'selected_model_family', 'modelFamily', 'model_family', 'resolvedModel', 'resolved_model']) || undefined,
    },
  }
}

function normalizeRoutingRoute(input: unknown, index: number, totalRequests: number): RoutingRoute {
  const source = record(input)
  const requests = numberValue(source, ['requests', 'count', 'requestsRouted', 'requests_routed'], 0) ?? 0
  const rawShare = numberValue(source, ['share', 'percentage', 'percent'])
  const share = rawShare == null
    ? totalRequests > 0 ? (requests / totalRequests) * 100 : 0
    : rawShare > 0 && rawShare <= 1 ? rawShare * 100 : rawShare
  const rawSuccessRate = numberValue(source, ['successRate', 'success_rate', 'success'])
  const id = textValue(source, ['id', 'routeId', 'route_id', 'modelRouteId', 'model_route_id', 'modelFamily', 'model_family'], `route-${index}`)
  return {
    id,
    label: textValue(source, ['label', 'routeLabel', 'route_label', 'modelRouteLabel', 'model_route_label', 'name'], id),
    requests,
    share: Math.round(Math.max(0, Math.min(100, share)) * 1_000) / 1_000,
    p95LatencyMs: numberValue(source, ['p95LatencyMs', 'p95_latency_ms', 'p95Latency', 'p95_latency']),
    successRate: rawSuccessRate == null
      ? null
      : Math.round((rawSuccessRate > 0 && rawSuccessRate <= 1 ? rawSuccessRate * 100 : rawSuccessRate) * 1_000) / 1_000,
  }
}

function normalizeRouting(input: unknown): RoutingDecision | null {
  const source = record(input)
  if (!source) return null
  const routeInput = array(first(source, ['routes', 'routeMix', 'route_mix', 'models', 'modelMix', 'model_mix']))
  const explicitTotal = numberValue(source, ['totalRequests', 'total_requests', 'requestsRouted', 'requests_routed', 'requests'])
  const summedTotal = routeInput.reduce<number>((sum, route) => sum + (numberValue(record(route), ['requests', 'count'], 0) ?? 0), 0)
  const totalRequests = explicitTotal ?? summedTotal
  const strategy = textValue(source, ['strategy', 'routingStrategy', 'routing_strategy', 'profile', 'profileId', 'profile_id'])
  const routes = routeInput.map((route, index) => normalizeRoutingRoute(route, index, totalRequests))
  if (!strategy && totalRequests === 0 && routes.length === 0) return null
  return {
    strategy: strategy || 'Not reported',
    requestsRouted: totalRequests,
    explanation: textValue(source, ['explanation', 'description', 'detail', 'reason']) || undefined,
    routes,
  }
}

function normalizeNode(input: unknown, index: number): SystemNode {
  const source = record(input)
  const kind = textValue(source, ['kind', 'type'], 'consumer')
  const allowedKinds: SystemNode['kind'][] = ['foundry', 'gateway', 'kafka', 'consumer', 'browser']
  return {
    id: textValue(source, ['id'], `node-${index}`),
    name: textValue(source, ['name', 'label'], 'Service'),
    detail: textValue(source, ['detail', 'description', 'endpoint']),
    kind: allowedKinds.includes(kind as SystemNode['kind']) ? kind as SystemNode['kind'] : 'consumer',
    health: normalizeHealth(first(source, ['health', 'status', 'state'])),
    metric: textValue(source, ['metric', 'value']) || undefined,
  }
}

export function normalizeSnapshot(input: unknown, previous: LabSnapshot = EMPTY_SNAPSHOT): LabSnapshot {
  const outer = record(input)
  const root = record(first(outer, ['snapshot', 'data'])) ?? outer
  if (!root) return previous
  const health = record(first(root, ['health', 'status']))
  const telemetry = record(first(root, ['telemetry', 'stream']))
  const metrics = first(root, ['kpis', 'metrics']) ?? first(telemetry, ['kpis', 'metrics'])
  const freshness = record(first(root, ['freshness']))
  const partitionInput = first(root, ['partitions', 'partitionTelemetry', 'partition_telemetry'])
    ?? first(telemetry, ['partitions', 'lanes'])
  const eventInput = first(root, ['events', 'liveEvents', 'live_events'])
  const traceInput = first(root, ['traces', 'recentTraces', 'recent_traces'])
  const systemInput = first(root, ['system', 'nodes', 'systemNodes', 'system_nodes'])
  const routingInput = first(root, ['routing', 'routingDecision', 'routing_decision', 'modelRouting', 'model_routing'])
  const hasActiveRun = Object.prototype.hasOwnProperty.call(root, 'activeRun')
    || Object.prototype.hasOwnProperty.call(root, 'active_run')
    || Object.prototype.hasOwnProperty.call(root, 'run')
  const runValue = Object.prototype.hasOwnProperty.call(root, 'activeRun')
    ? root.activeRun
    : Object.prototype.hasOwnProperty.call(root, 'active_run')
      ? root.active_run
      : root.run
  const kafkaHealthValue = first(health, ['kafka']) ?? first(root, ['kafkaHealth', 'kafka_health'])
  const foundryHealthValue = first(health, ['foundry', 'aiFoundry', 'ai_foundry'])
    ?? first(root, ['foundryHealth', 'foundry_health'])
  const freshnessAgeMs = numberValue(freshness, ['ageMs', 'age_ms'])
  const metricRecord = record(metrics)
  const metricsWithFreshness = metricRecord && freshnessAgeMs != null
    ? { ...metricRecord, telemetryAgeSeconds: freshnessAgeMs / 1_000 }
    : metrics
  return {
    sessionId: textValue(root, ['sessionId', 'session_id'], previous.sessionId ?? '') || undefined,
    capturedAt: textValue(root, ['capturedAt', 'captured_at', 'timestamp', 'generatedAt', 'generated_at'], previous.capturedAt ?? '') || undefined,
    kafkaHealth: kafkaHealthValue == null ? previous.kafkaHealth : normalizeHealth(kafkaHealthValue),
    foundryHealth: foundryHealthValue == null ? previous.foundryHealth : normalizeHealth(foundryHealthValue),
    streamRate: numberValue(root, ['streamRate', 'stream_rate', 'eventsPerSecond', 'events_per_second'])
      ?? numberValue(telemetry, ['rate', 'streamRate', 'stream_rate'])
      ?? numberValue(record(metrics), ['eventsPerSecond', 'events_per_second'], previous.streamRate),
    run: hasActiveRun ? normalizeRun(runValue) : previous.run,
    kpis: metricsWithFreshness !== undefined ? normalizeKpis(metricsWithFreshness, previous.kpis) : previous.kpis,
    partitions: partitionInput !== undefined ? array(partitionInput).map(normalizePartition) : previous.partitions,
    events: eventInput !== undefined ? array(eventInput).map(normalizeEvent) : previous.events,
    traces: traceInput !== undefined ? array(traceInput).map(normalizeTrace) : previous.traces,
    system: systemInput !== undefined ? array(systemInput).map(normalizeNode) : previous.system,
    routing: routingInput !== undefined ? normalizeRouting(routingInput) : previous.routing,
  }
}

function cap<T>(items: T[], maximum: number): T[] {
  return items.slice(0, maximum)
}

export function reduceStreamMessage(previous: LabSnapshot, input: unknown, eventType = 'message'): LabSnapshot {
  const envelope = record(input) as StreamEnvelope | undefined
  const type = String(envelope?.type ?? eventType).toLowerCase()
  const payload = envelope?.data ?? envelope?.payload ?? input
  if (type.includes('snapshot')) return normalizeSnapshot(payload, previous)
  if (type.includes('metric') || type === 'kpi') return normalizeSnapshot({ metrics: payload }, previous)
  if (type.includes('telemetry')) {
    const event = normalizeEvent(payload)
    return { ...previous, events: cap([event, ...previous.events.filter((item) => item.id !== event.id)], 200) }
  }
  if (type.includes('partition')) {
    const partition = normalizePartition(payload)
    return {
      ...previous,
      partitions: [partition, ...previous.partitions.filter((item) => item.id !== partition.id)],
    }
  }
  if (type.includes('trace')) {
    const trace = normalizeTrace(payload)
    return { ...previous, traces: cap([trace, ...previous.traces.filter((item) => item.id !== trace.id)], 100) }
  }
  if (type.includes('routing')) return { ...previous, routing: normalizeRouting(payload) }
  if (type.includes('event')) {
    const event = normalizeEvent(payload)
    return { ...previous, events: cap([event, ...previous.events.filter((item) => item.id !== event.id)], 200) }
  }
  if (type.includes('run')) return { ...previous, run: normalizeRun(payload) }
  return normalizeSnapshot(input, previous)
}

export class ApiError extends Error {
  constructor(message: string, readonly status?: number) {
    super(message)
    this.name = 'ApiError'
  }
}

async function jsonRequest(path: string, init?: RequestInit): Promise<unknown> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers: {
      Accept: 'application/json',
      ...(init?.body ? { 'Content-Type': 'application/json' } : {}),
      ...init?.headers,
    },
    cache: 'no-store',
  })
  if (!response.ok) {
    const body = await response.text().catch(() => '')
    let message = body
    try {
      const parsed = JSON.parse(body) as { error?: { message?: string } }
      message = parsed.error?.message || body
    } catch {
      // Keep a non-JSON server response as the diagnostic message.
    }
    throw new ApiError(message || `${response.status} ${response.statusText}`, response.status)
  }
  if (response.status === 204) return null
  return response.json()
}

export async function fetchConfig(): Promise<LabConfig> {
  return normalizeConfig(await jsonRequest('/api/v1/config'))
}

export async function fetchSnapshot(): Promise<LabSnapshot> {
  return normalizeSnapshot(await jsonRequest('/api/v1/snapshot'))
}

export async function createRun(input: RunInput): Promise<unknown> {
  return jsonRequest('/api/v1/runs', {
    method: 'POST',
    body: JSON.stringify({
      workload: input.workloadId,
      scenario: input.scenarioId,
      traffic: input.requestRate,
      modelProfile: input.modelProfile ?? input.modelId,
      modelId: input.modelId ?? input.modelProfile,
      ...(input.prompt ? { prompt: input.prompt } : {}),
    }),
  })
}

export async function stopRun(runId: string): Promise<unknown> {
  return jsonRequest(`/api/v1/runs/${encodeURIComponent(runId)}/stop`, { method: 'POST' })
}

export async function deleteSession(): Promise<void> {
  await jsonRequest('/api/v1/session', { method: 'DELETE' })
}

export function subscribeToStream(options: {
  onOpen: () => void
  onMessage: (payload: unknown, eventType: string) => void
  onError: () => void
}): () => void {
  const stream = new EventSource(`${API_BASE}/api/v1/stream`)
  stream.onopen = options.onOpen
  stream.onerror = options.onError
  const handler = (event: MessageEvent) => {
    try {
      options.onMessage(JSON.parse(event.data), event.type)
    } catch {
      options.onMessage({ type: event.type, data: event.data }, event.type)
    }
  }
  stream.onmessage = handler
  const namedEvents = ['snapshot', 'metrics', 'metric', 'partition', 'telemetry', 'event', 'trace', 'routing', 'run']
  for (const name of namedEvents) stream.addEventListener(name, handler as EventListener)
  return () => {
    for (const name of namedEvents) stream.removeEventListener(name, handler as EventListener)
    stream.close()
  }
}
