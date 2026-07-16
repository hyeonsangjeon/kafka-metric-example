import { ChevronDown, Database, LoaderCircle, LockKeyhole, MessageSquareText, Play, Square, TriangleAlert } from 'lucide-react'
import { useState } from 'react'
import type { ConnectionState, LabConfig, RunInput, RunState } from '../types'

interface RunConsoleProps {
  config: LabConfig
  run: RunState | null
  connection: ConnectionState
  pending: 'run' | 'stop' | 'reset' | null
  onStart: (input: RunInput) => Promise<void>
  onStop: () => Promise<void>
}

function friendly(value: string): string {
  return value.replaceAll('_', ' ').replace(/\b\w/g, (letter) => letter.toUpperCase())
}

export function RunConsole({ config, run, connection, pending, onStart, onStop }: RunConsoleProps) {
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
  const isRunning = run?.status === 'running' || run?.status === 'starting' || run?.status === 'stopping'
  const disabled = connection !== 'connected' || !workloadId || pending !== null

  const submit = async () => {
    if (isRunning) {
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
            <select value={workloadId} onChange={(event) => setWorkloadChoice(event.target.value)} disabled={isRunning || !config.workloads.length}>
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
            <select value={scenarioId} onChange={(event) => setScenarioChoice(event.target.value)} disabled={isRunning || !config.scenarios.length}>
              {!config.scenarios.length ? <option value="">Waiting for config</option> : null}
              {config.scenarios.map((option) => <option key={option.id} value={option.id}>{friendly(option.label)}</option>)}
            </select>
            <ChevronDown size={17} />
          </span>
        </label>

        <fieldset className="profile-field" disabled={isRunning || !profiles.length} aria-describedby="execution-profile-description">
          <legend>Execution profile</legend>
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
              disabled={isRunning}
              aria-label="Requests per run"
            />
            <output>{requestRate} requests</output>
          </span>
        </label>

        <button className={`primary-button ${isRunning ? 'primary-button--stop' : ''}`} onClick={submit} disabled={disabled && !isRunning}>
          {pending === 'run' || pending === 'stop' ? <LoaderCircle className="spin" size={18} /> : isRunning ? <Square size={16} fill="currentColor" /> : <Play size={18} fill="currentColor" />}
          <span>{isRunning ? 'Stop workload' : connection === 'connected' ? 'Run workload' : 'Connect to run'}</span>
        </button>

        <label className="prompt-field">
          <span><MessageSquareText size={15} /> Prompt <small>optional</small></span>
          <textarea
            value={prompt}
            onChange={(event) => setPrompt(event.target.value)}
            placeholder="Use the workload's safe default, or enter a prompt for this run"
            maxLength={8_000}
            disabled={isRunning}
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
