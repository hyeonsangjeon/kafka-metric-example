# Microsoft Foundry setup

Foundry Stream Lab keeps three provider paths explicit. The deterministic
simulator is the default, Ollama is a local fixed-model path, and Microsoft
Foundry is an opt-in live path. There is no automatic fallback between them.

The Foundry path is deliberately a **Model Router demo**, not a general agent
application. It compares one fixed deployment with two independently configured
router deployments:

- `router-default`: Balanced routing over the current supported model set.
- `router-advanced`: Cost or Quality routing, optionally constrained to a model
  subset.

Router mode and subset are deployment properties. They are never changed by an
application request, and a control-plane update can take several minutes to
propagate.

## Foundry capability scope

| Capability | Used here | Boundary |
| --- | --- | --- |
| Responses API | Yes | Stateless calls with `store(false)`. |
| Model Router | Yes | Fixed vs default Balanced vs advanced Cost/Quality deployments. |
| Managed tracing | Yes | `tools/foundry/live_smoke.py` sends OpenTelemetry spans to project-connected Application Insights with prompt/response capture disabled; this does not imply automatic tracing for every Java app call. |
| Model Router Auto Evaluation | Yes | A pinned Microsoft toolkit compares quality, latency, model distribution, and cost only when current pricing is configured. |
| Foundry Evaluation | Yes — optional runtime post-processing | The recorded run submitted completed local results to managed graders; Foundry Evaluation did not directly invoke Model Router. |
| Agent Service conversations, threads/runs, or long-term memory | No | The lab does not need persistent agent state. |
| Function calling or external tools | No | No external action is required for a routing benchmark. |
| Azure AI Search RAG | No | Synthetic Kafka prompts are supplied directly and contain no private corpus. |
| Prompt Flow | No | The workload path is Java plus the Responses API; adding a second orchestration runtime would obscure the comparison. |
| Fine-tuning | No | This demo changes routing policy and model subset, not model weights. |

This narrow boundary makes `response.model`, latency, token use, Kafka delivery,
and failure behavior attributable to the selected routing profile.

Official references:

- [Use Model Router](https://learn.microsoft.com/azure/foundry/openai/how-to/model-router)
- [Responses API with Model Router](https://learn.microsoft.com/azure/foundry/openai/how-to/responses-model-routing)
- [Create a Foundry project](https://learn.microsoft.com/azure/foundry/how-to/create-projects)
- [Trace applications with OpenTelemetry](https://learn.microsoft.com/azure/foundry/observability/how-to/trace-agent-client-side)
- [Model Router Auto Evaluation](https://github.com/microsoft-foundry/Model-Router-Auto-Evaluation)
- [Keyless Microsoft Entra authentication](https://learn.microsoft.com/azure/foundry/foundry-models/how-to/configure-entra-id)

## Provider profiles

| Provider | Profiles exposed to the UI | Provider request |
| --- | --- | --- |
| `simulated` | `fixed`, `router-balanced` | None; seeded local behavior. |
| `ollama` | `ollama-fixed` | `POST {OLLAMA_BASE_URL}/responses`. |
| `foundry` | `fixed`, plus either or both live router profiles | Foundry project Responses API. |

The simulator's `router-balanced` profile is a deterministic teaching aid. It
emits synthetic `fast`, `general`, and `reasoning` routes so the dashboard works
before Azure resources exist. It is not a local implementation of Microsoft's
Model Router.

## Environment reference

| Variable | Default | Meaning |
| --- | --- | --- |
| `AI_PROVIDER` | `simulated` | `simulated`, `ollama`, or `foundry`; takes precedence over `AI_MODE`. |
| `AI_MODE` | unset | Compatibility alias read only when `AI_PROVIDER` is absent. |
| `EVENT_TRANSPORT` | `kafka` in Compose | `kafka` or ephemeral `memory`. |
| `APP_PORT` | `8080` | Gateway and dashboard port. |
| `OLLAMA_BASE_URL` | `http://127.0.0.1:11434/v1` | OpenAI-compatible base URL; `/responses` is appended. |
| `OLLAMA_MODEL` | unset | Exact model already pulled into Ollama. |
| `OLLAMA_REQUIRE_LOOPBACK` | `true` | Reject a non-loopback Ollama URL unless explicitly disabled. |
| `FOUNDRY_PROJECT_ENDPOINT` | unset | Project endpoint from the Foundry project page. |
| `FOUNDRY_MODEL` | unset | Fixed model deployment used by `fixed`. |
| `FOUNDRY_ROUTER_DEFAULT_MODEL` | unset | Default Balanced router deployment used by `router-default`. |
| `FOUNDRY_ROUTER_ADVANCED_MODEL` | unset | Custom router deployment used by `router-advanced`. |
| `FOUNDRY_ROUTER_ADVANCED_PROFILE` | `quality` in the Java runtime | `cost` or `quality` display/validation metadata; it does not alter Azure. |
| `FOUNDRY_ROUTER_MODEL` | unset | Legacy single-router deployment variable. |
| `FOUNDRY_ROUTER_PROFILE` | `balanced` | Legacy `balanced`, `cost`, or `quality` metadata. |
| `MAX_CLOUD_REQUESTS_PER_RUN` | `10` | Hard cap on Foundry calls in one application run. |

When only the legacy pair is present, `balanced` maps to `router-default` and
`cost`/`quality` maps to `router-advanced`. New configurations should use the
dual-router variables.

## Local paths

The complete simulator path is credential-free:

```bash
docker compose up --build
```

For Ollama, run the gateway on the host so the default loopback policy remains
meaningful:

```bash
make kafka

cd app
mvn -B package

AI_PROVIDER=ollama \
EVENT_TRANSPORT=kafka \
KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
OLLAMA_BASE_URL='http://127.0.0.1:11434/v1' \
OLLAMA_MODEL='YOUR-LOCAL-MODEL' \
OLLAMA_REQUIRE_LOOPBACK=true \
java -jar target/foundry-stream-lab.jar
```

Ollama is fixed-model only. An unavailable local endpoint fails visibly and is
never replaced by a paid Foundry call.

## Provision the live Foundry demo

The `infra/` plan creates one tagged non-production resource group containing:

- an `AIServices` Foundry account and project with system-assigned identities;
- one GPT-5.4 mini fixed deployment;
- Model Router `2025-11-18` in default Balanced mode;
- a Cost router restricted to GPT-5.4 nano, mini, and full;
- workspace-based Application Insights with a 30-day Log Analytics retention
  period and 0.1 GB daily ingestion cap;
- the project monitoring connection and scoped RBAC.

Review the plan, then apply it with the selected Azure CLI subscription:

```bash
az login
az account show

export AZURE_UNIQUE_SUFFIX='your-unique-lowercase-suffix'
./infra/provision.sh --dry-run
./infra/provision.sh --apply
./infra/verify.sh
```

The Foundry account keeps local/key authentication disabled. The scripts never
retrieve or print account keys, connection strings, bearer tokens, tenant IDs,
subscription IDs, or principal IDs. See [the infrastructure guide](../infra/README.md)
for overrides, ownership guards, verification, and explicit cleanup.

## Run all three live profiles

Run Kafka and the Java gateway on the host so `DefaultAzureCredential` can use
the Azure CLI session:

```bash
make kafka

cd app
mvn -B package

AI_PROVIDER=foundry \
EVENT_TRANSPORT=kafka \
KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
FOUNDRY_PROJECT_ENDPOINT='https://YOUR-RESOURCE.services.ai.azure.com/api/projects/YOUR-PROJECT' \
FOUNDRY_MODEL='YOUR-FIXED-DEPLOYMENT' \
FOUNDRY_ROUTER_DEFAULT_MODEL='YOUR-DEFAULT-ROUTER' \
FOUNDRY_ROUTER_ADVANCED_MODEL='YOUR-ADVANCED-ROUTER' \
FOUNDRY_ROUTER_ADVANCED_PROFILE='cost' \
MAX_CLOUD_REQUESTS_PER_RUN=10 \
java -jar target/foundry-stream-lab.jar
```

Start the Vite dashboard from a second terminal and open
[http://127.0.0.1:5173](http://127.0.0.1:5173):

```bash
cd web
npm ci
npm run dev
```

Run `fixed`, `router-default`, and `router-advanced` without changing the prompt,
workload, traffic, or failure scenario. Foundry's raw `response.model` value is
reduced to a hardcoded family label before application telemetry is emitted; an
unknown model becomes `other`.

## Managed tracing

The tracing smoke tool calls all three deployments through the project client,
loads its Application Insights connection, and force-flushes OpenTelemetry:

```bash
export FOUNDRY_PROJECT_ENDPOINT='https://YOUR-RESOURCE.services.ai.azure.com/api/projects/YOUR-PROJECT'
export FOUNDRY_MODEL='YOUR-FIXED-DEPLOYMENT'
export FOUNDRY_ROUTER_DEFAULT_MODEL='YOUR-DEFAULT-ROUTER'
export FOUNDRY_ROUTER_ADVANCED_MODEL='YOUR-ADVANCED-ROUTER'

uv run --python 3.12 \
  --with-requirements tools/foundry/requirements.txt \
  tools/foundry/live_smoke.py
```

Prompt/response capture and baggage propagation are explicitly disabled before
instrumentation is imported. Trace ingestion is asynchronous and commonly takes
two to five minutes. The printed trace IDs can be used to verify spans in Azure
Monitor without logging content.

## Router evaluation

`tools/eval/` pins a specific commit and tree from Microsoft's Model Router Auto
Evaluation toolkit. It supplies a synthetic Kafka dataset and compares both
router deployments against the same fixed baseline and judge with Entra auth:

```bash
python3 tools/eval/eval.py validate --remote-pin
python3 tools/eval/eval.py bootstrap

export AZURE_MODEL_ROUTER_ENDPOINT='https://YOUR-RESOURCE.services.ai.azure.com'
export AZURE_OPENAI_ENDPOINT='https://YOUR-RESOURCE.services.ai.azure.com'
export AZURE_MODEL_ROUTER_BASIC_DEPLOYMENT='YOUR-DEFAULT-ROUTER'
export AZURE_MODEL_ROUTER_ADVANCED_DEPLOYMENT='YOUR-ADVANCED-ROUTER'
export AZURE_BASELINE_DEPLOYMENT='YOUR-FIXED-DEPLOYMENT'
export AZURE_JUDGE_DEPLOYMENT='YOUR-FIXED-DEPLOYMENT'

python3 tools/eval/eval.py matrix --auth entra
```

Generated reports contain model responses and remain under ignored
`tools/eval/results/`. The committed dataset is intentionally small, so its
quality and latency output is directional rather than statistically reliable.
The wrapper refuses to invent a cost conclusion when the current routed models
do not have an explicitly configured price.

After local inference, completed results can be sent to managed Foundry graders:

```bash
export AZURE_AI_PROJECT_ENDPOINT='https://YOUR-RESOURCE.services.ai.azure.com/api/projects/YOUR-PROJECT'
export AZURE_AI_MODEL_DEPLOYMENT_NAME='YOUR-JUDGE-DEPLOYMENT'

python3 tools/eval/eval.py cloud-eval \
  --input-dir tools/eval/results/MATRIX/RUN \
  --dry-run
python3 tools/eval/eval.py cloud-eval \
  --input-dir tools/eval/results/MATRIX/RUN
```

See [the evaluation guide](../tools/eval/README.md) for pin verification, data
handling, sample-size guidance, and the API-key fallback. The sanitized
[2026-07-16 live evaluation record](live-evaluation.md) captures the aggregate
results. The public [evidence bundle](evidence/2026-07-16-foundry-live/README.md)
adds charts, sanitized managed-evaluation/tracing/usage exports, screenshots,
and one manually reviewed synthetic response triplet while excluding bulk raw
results, endpoint URLs, and identity/evaluation locator IDs. Disposable demo
resource names remain in the inventory for cleanup provenance.

## Privacy, identity, and cost boundary

- Prompts go only to the selected provider. Prompt and response bodies never go
  to Kafka, the projection, or browser storage.
- Foundry application requests set `store(false)`.
- Endpoints, raw deployment names, credentials, tenant IDs, subscription IDs,
  and customer identifiers are excluded from Kafka events, projections, and
  browser storage. Managed Application Insights traces can include the service
  target and deployment name as standard dependency metadata; prompt/response
  capture remains disabled.
- Route/model data uses bounded aliases from a hardcoded mapping.
- The app caps paid calls per run, while the evaluation tool requires an explicit
  command and stores response-bearing reports only in an ignored local path.
- The Log Analytics daily cap limits ingestion, not total Azure spend. Delete the
  dedicated resource group after the demo if it is no longer needed.

## Historical live verification record

On 2026-07-16, this workflow was exercised from an Apple M1 host against a new
East US 2 Foundry project using Microsoft Entra authentication and local auth
disabled. Fixed GPT-5.4 mini, default Model Router `2025-11-18`, and a Cost-mode
router constrained to GPT-5.4 nano/mini/full all returned successful Responses
API calls. The Cost router selected GPT-5.4 nano in the smoke run. Application
Insights ingested spans for all three emitted trace IDs with content capture
disabled.

The environment was intentionally disposable. Its evidence was published before
cleanup; the current gate and eventual deletion/purge result are recorded in
[cleanup-summary.json](evidence/2026-07-16-foundry-live/data/cleanup-summary.json).

This record proves connectivity and integration, not production readiness or a
general quality claim. The included broker is local and unauthenticated, public
Foundry endpoints remain enabled for the demo, and the small synthetic evaluation
set cannot replace a representative workload of at least 100 prompts.
