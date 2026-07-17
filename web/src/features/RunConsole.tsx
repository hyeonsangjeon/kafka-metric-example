import {
  ChevronDown,
  Database,
  GitCompareArrows,
  LoaderCircle,
  LockKeyhole,
  MessageSquareText,
  Play,
  Square,
  TriangleAlert,
} from 'lucide-react'
import { useState } from 'react'
import type { ComparisonInput, ComparisonState, ConnectionState, LabConfig, RunInput, RunState } from '../types'

interface RunConsoleProps {
  config: LabConfig
  run: RunState | null
  comparison: ComparisonState | null
  connection: ConnectionState
  pending: 'run' | 'stop' | 'compare' | 'stop-comparison' | 'reset' | null
  onStart: (input: RunInput) => Promise<void>
  onStop: () => Promise<void>
  onCompare: (input: ComparisonInput) => Promise<void>
  onStopComparison: () => Promise<void>
}

function friendly(value: string): string {
  return value.replaceAll('_', ' ').replace(/\b\w/g, (letter) => letter.toUpperCase())
}

export function RunConsole({
  config,
  run,
  comparison,
  connection,
  pending,
  onStart,
  onStop,
  onCompare,
  onStopComparison,
}: RunConsoleProps) {
  const [workloadChoice, setWorkloadChoice] = useState('')
  const [scenarioChoice, setScenarioChoice] = useState('')
  const [profileChoice, setProfileChoice] = useState('')
  const [rateChoice, setRateChoice] = useState<number | null>(null)
  const [prompt, setPrompt] = useState('')
  const workloadId = workloadChoice || config.defaults.workloadId || config.workloads[0]?.id || ''
  const scenarioId = scenarioChoice || config.defaults.scenarioId || config.scenarios[0]?.id || ''
  const profiles = config.modelProfiles.filter((profile) => profile.available !== false)
  const modelProfile = profileChoice || config.defaults.modelProfile || config.defaults.modelId || profiles[0]?.id || ''
  const selectedProfile = profiles.find((profile) => profile.id === modelProfile)
  const maxTraffic = config.maxTrafficPerRun ?? 30
  const requestRate = Math.min(rateChoice ?? config.defaults.requestRate ?? 12, maxTraffic)
  const maxComparisonTraffic = config.comparison?.maxTrafficPerProfile ?? maxTraffic
  const comparisonTraffic = Math.min(requestRate, maxComparisonTraffic)
  const runActive = run?.status === 'running' || run?.status === 'starting' || run?.status === 'stopping'
  const comparisonActive = comparison?.status === 'queued' || comparison?.status === 'running'
  const normalRunActive = runActive && !comparisonActive
  const interactionActive = runActive || comparisonActive
  const comparisonAvailable = config.comparison?.enabled === true
  const cannotStart = connection !== 'connected' || !workloadId

  const submitRun = async () => {
    if (normalRunActive) {
      await onStop().catch(() => undefined)
      return
    }
    if (!workloadId) return
    await onStart({
      workloadId,
      scenarioId: scenarioId || undefined,
      modelProfile: modelProfile || undefined,
      modelId: modelProfile || undefined,
      requestRate,
      prompt: prompt.trim() || undefined,
    }).catch(() => undefined)
  }

  const submitComparison = async () => {
    if (comparisonActive) {
      await onStopComparison().catch(() => undefined)
      return
    }
    if (!workloadId || !comparisonAvailable) return
    await onCompare({
      workload: workloadId,
      scenario: scenarioId || undefined,
      traffic: comparisonTraffic,
      prompt: prompt.trim() || undefined,
    }).catch(() => undefined)
  }

  return (
    <section className="panel run-console" aria-labelledby="run-console-title">
      <div className="panel-heading run-console__heading">
        <div>
          <h1 id="run-console-title">Run console</h1>
          <p>Send a bounded AI workload and watch its telemetry flow through Kafka.</p>
        </div>
        {run?.id ? <span className={`run-badge run-badge--${run.status}`}>{run.id}</span> : null}
      </div>

      <div className="run-console__controls">
        <label className="select-field">
          <span>Workload</span>
          <span className="select-field__control">
            <Database size={17} />
            <select value={workloadId} onChange={(event) => setWorkloadChoice(event.target.value)} disabled={interactionActive || !config.workloads.length}>
              {!config.workloads.length ? <option value="">Waiting for config</option> : null}
              {config.workloads.map((option) => <option key={option.id} value={option.id}>{friendly(option.label)}</option>)}
            </select>
            <ChevronDown size={17} />
          </span>
        </label>

        <label className="select-field">
          <span>Failure scenario</span>
          <span className="select-field__control">
            <TriangleAlert size={17} />
            <select value={scenarioId} onChange={(event) => setScenarioChoice(event.target.value)} disabled={interactionActive || !config.scenarios.length}>
              {!config.scenarios.length ? <option value="">Waiting for config</option> : null}
              {config.scenarios.map((option) => <option key={option.id} value={option.id}>{friendly(option.label)}</option>)}
            </select>
            <ChevronDown size={17} />
          </span>
        </label>

        <fieldset className="profile-field" disabled={interactionActive || !profiles.length} aria-describedby="execution-profile-description">
          <legend>Execution profile <small>(single run)</small></legend>
          <div className="profile-field__options">
            {profiles.length ? profiles.map((profile) => {
              const isRouter = profile.kind === 'router' || profile.id.toLowerCase().includes('router')
              return (
                <label className={`profile-option ${isRouter ? 'profile-option--router' : ''}`} key={profile.id}>
                  <input
                    type="radio"
                    name="execution-profile"
                    value={profile.id}
                    checked={profile.id === modelProfile}
                    onChange={(event) => setProfileChoice(event.target.value)}
                  />
                  <span>
                    <strong>{profile.label}</strong>
                    {profile.strategy ? <small>{friendly(profile.strategy)}</small> : null}
                  </span>
                </label>
              )
            }) : <span className="profile-field__empty">Waiting for config</span>}
          </div>
          <p id="execution-profile-description" aria-live="polite">
            {selectedProfile
              ? selectedProfile.description || (selectedProfile.kind === 'router' ? 'Routes each request by workload signals and the deployment strategy.' : 'Uses one deployment for every request in this run.')
              : 'Execution profiles load from the lab service.'}
          </p>
        </fieldset>

        <label className="rate-field">
          <span>Traffic</span>
          <span className="rate-field__control">
            <input
              type="range"
              min="1"
              max={maxTraffic}
              value={requestRate}
              onChange={(event) => setRateChoice(Number(event.target.value))}
              disabled={interactionActive}
              aria-label="Requests per run"
            />
            <output>
              <span>{requestRate} requests</span>
              {comparisonAvailable && comparisonTraffic < requestRate
                ? <small>Compare: {comparisonTraffic}/profile</small>
                : null}
            </output>
          </span>
        </label>

        <div className="run-console__actions">
          <button
            className={`primary-button ${normalRunActive ? 'primary-button--stop' : ''}`}
            onClick={submitRun}
            disabled={pending !== null || comparisonActive || (!normalRunActive && cannotStart)}
          >
            {pending === 'run' || pending === 'stop'
              ? <LoaderCircle className="spin" size={18} />
              : normalRunActive
                ? <Square size={16} fill="currentColor" />
                : <Play size={18} fill="currentColor" />}
            <span>{normalRunActive ? 'Stop workload' : connection === 'connected' ? 'Run workload' : 'Connect to run'}</span>
          </button>

          <button
            className={`comparison-button ${comparisonActive ? 'comparison-button--stop' : ''}`}
            onClick={submitComparison}
            disabled={pending !== null || (!comparisonActive && (runActive || cannotStart || !comparisonAvailable))}
            title={!comparisonAvailable ? config.comparison?.unavailableReason : undefined}
          >
            {pending === 'compare' || pending === 'stop-comparison'
              ? <LoaderCircle className="spin" size={18} />
              : comparisonActive
                ? <Square size={15} fill="currentColor" />
                : <GitCompareArrows size={18} />}
            <span>{comparisonActive ? 'Stop comparison' : comparisonAvailable ? 'Compare profiles' : 'Compare unavailable'}</span>
          </button>
          <small>Sequential comparison · same input · bounded calls.</small>
        </div>

        <label className="prompt-field">
          <span><MessageSquareText size={15} /> Prompt <small>optional</small></span>
          <textarea
            value={prompt}
            onChange={(event) => setPrompt(event.target.value)}
            placeholder="Use the workload's safe default, or enter a prompt for this run"
            maxLength={8_000}
            disabled={interactionActive}
            rows={2}
          />
          <small>{prompt.length.toLocaleString()} / 8,000</small>
        </label>
      </div>

      <div className="privacy-strip">
        <LockKeyhole size={15} />
        <span><strong>Telemetry only.</strong> The prompt goes to the selected provider, but prompt and response bodies are never persisted or published to Kafka.</span>
      </div>
    </section>
  )
}
