# Provider and Model Router setup

Foundry Stream Lab has three explicit provider paths. The credential-free
simulator is the default, Ollama is local-only by default, and Microsoft Foundry
is opt-in. There is no automatic fallback between them.

The Foundry adapter uses the current Responses API through the Java
`azure-ai-agents` 2.x SDK. Responses and Conversations are the current runtime
primitives; the lab does not use legacy Assistants threads/runs. Requests are
stateless and set `store(false)`, so this demo does not opt in to Conversations
or long-term agent memory.

Official references:

- [Ollama OpenAI compatibility](https://docs.ollama.com/api/openai-compatibility)
- [Microsoft Foundry Responses API with Model Router](https://learn.microsoft.com/azure/foundry/openai/how-to/responses-model-routing)
- [Microsoft Foundry Model Router concepts](https://learn.microsoft.com/azure/foundry/openai/concepts/model-router)
- [Responses REST reference](https://learn.microsoft.com/rest/api/microsoft-foundry/azureopenai/responses)
- [Keyless Microsoft Entra authentication](https://learn.microsoft.com/azure/foundry/foundry-models/how-to/configure-entra-id)

## Provider profiles

| Provider | Profiles exposed to the UI | Provider request |
| --- | --- | --- |
| `simulated` | `fixed`, `router-balanced` | None; seeded local behavior |
| `ollama` | `ollama-fixed` | `POST {OLLAMA_BASE_URL}/responses` |
| `foundry` | `fixed`; `router-balanced` when a router deployment is configured | Foundry project Responses API |

The simulator's router is a deterministic teaching aid. It emits synthetic
`fast`, `general`, and `reasoning` routes so the dashboard can demonstrate the
same comparison before Azure resources exist. It is not a local implementation
of Microsoft's Model Router.

## Environment reference

| Variable | Default | Required when | Meaning |
| --- | --- | --- | --- |
| `AI_PROVIDER` | `simulated` | Never | `simulated`, `ollama`, or `foundry`. Takes precedence over `AI_MODE`. |
| `AI_MODE` | unset | Never | Legacy alias for the same provider values, read only when `AI_PROVIDER` is absent. |
| `EVENT_TRANSPORT` | `kafka` in Compose | Host/test choice | `kafka` or ephemeral `memory`. |
| `APP_PORT` | `8080` | Never | Gateway and dashboard port. |
| `OLLAMA_BASE_URL` | `http://127.0.0.1:11434/v1` | Ollama override only | OpenAI-compatible base URL; `/responses` is appended. |
| `OLLAMA_MODEL` | unset | `AI_PROVIDER=ollama` | Exact name of a model already pulled into Ollama. |
| `OLLAMA_REQUIRE_LOOPBACK` | `true` | Never | Rejects a non-loopback Ollama URL unless explicitly disabled. |
| `FOUNDRY_PROJECT_ENDPOINT` | unset | `AI_PROVIDER=foundry` | Project endpoint copied from the Foundry project page. |
| `FOUNDRY_MODEL` | unset | `AI_PROVIDER=foundry` | Fixed model deployment used by the `fixed` profile. |
| `FOUNDRY_ROUTER_MODEL` | unset | Router comparison only | Model Router deployment; its presence adds `router-balanced`. |
| `FOUNDRY_ROUTER_PROFILE` | `balanced` | Never | `balanced`, `cost`, or `quality` deployment-intent metadata; does not reconfigure routing. |
| `MAX_CLOUD_REQUESTS_PER_RUN` | `10` | Never | Hard cap on Foundry calls in one run. |
| `KAFKA_BOOTSTRAP_SERVERS` | `broker:19092` in Compose | Kafka host override only | Broker address. |
| `KAFKA_TOPIC` | `foundry.telemetry.v1` | Never | Versioned telemetry topic. |
| `KAFKA_DLQ_TOPIC` | `foundry.telemetry.dlq.v1` | Never | Invalid-event dead-letter topic. |

## Path 1: credential-free simulator

The full Docker Compose quick start uses the simulator and makes no provider
network request:

```bash
docker compose up --build
```

Choose `fixed` and `router-balanced` in consecutive runs with the same
workload, prompt, traffic, and failure scenario. Route labels, latency, tokens,
and outcomes are repeatable for the same seeded request sequence.

## Path 2: local Ollama fixed model

Pull and serve a local model using Ollama, then run the gateway on the host.
Host execution keeps `127.0.0.1` scoped to the machine running Ollama; inside
the application container it would refer to that container instead.

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

Serve the dashboard from a second terminal, then open
[http://127.0.0.1:5173](http://127.0.0.1:5173):

```bash
cd web
npm ci
npm run dev
```

The adapter uses Ollama's OpenAI-compatible `/v1/responses` endpoint. Ollama
Responses are treated as stateless by this lab. If Ollama is unavailable or
rejects the request, the run records that error; it never falls back to
Foundry.

On Docker Desktop, an explicit advanced opt-out can reach a host Ollama service
with `OLLAMA_BASE_URL=http://host.docker.internal:11434/v1` and
`OLLAMA_REQUIRE_LOOPBACK=false`. That weakens the loopback guard, so verify the
hostname and local network exposure first. The recommended demo path remains
running the gateway on the host with the guard enabled.

## Path 3: Foundry fixed vs Model Router

### Prerequisites

1. Create a dedicated non-production Microsoft Foundry project.
2. Deploy one fixed model and one Model Router deployment.
3. Configure the router deployment itself for Balanced, Cost, or Quality mode.
4. Grant your identity the Foundry project user role.
5. Install Azure CLI, run `az login`, and copy the project endpoint.

Start Kafka, build the gateway, and run it on the host so
`DefaultAzureCredential` can use the Azure CLI session:

```bash
docker compose up -d broker topic-init

cd app
mvn -B package

AI_PROVIDER=foundry \
EVENT_TRANSPORT=kafka \
KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
FOUNDRY_PROJECT_ENDPOINT='https://YOUR-RESOURCE.services.ai.azure.com/api/projects/YOUR-PROJECT' \
FOUNDRY_MODEL='YOUR-FIXED-DEPLOYMENT' \
FOUNDRY_ROUTER_MODEL='YOUR-MODEL-ROUTER-DEPLOYMENT' \
FOUNDRY_ROUTER_PROFILE='balanced' \
MAX_CLOUD_REQUESTS_PER_RUN=10 \
java -jar target/foundry-stream-lab.jar
```

In another terminal, run the dashboard:

```bash
cd web
npm ci
npm run dev
```

Open [http://127.0.0.1:5173](http://127.0.0.1:5173). Run `fixed`, then
`router-balanced`, without changing the workload inputs. The value returned by
the Foundry response is reduced to a hardcoded, privacy-safe model-family label
before telemetry is emitted.

`FOUNDRY_ROUTER_PROFILE` does not select Balanced, Cost, or Quality per
request. Those modes are properties of the router deployment. For example, a
deployment configured for Cost should be paired with
`FOUNDRY_ROUTER_PROFILE=cost` so the UI describes reality; changing only the
environment value changes the label, not the deployment.

The stable API profile ID remains `router-balanced` for this demo even when the
metadata label is Cost or Quality. Use `routeStrategy` in the snapshot and
trace metadata to read the configured strategy.

## Azure identity

The same `DefaultAzureCredential` chain supports managed identity. Assign the
identity at the Foundry project boundary and provide only the endpoint and
deployment configuration. Do not bake access tokens, client secrets, or
endpoints into the image.

## Privacy and cost boundary

- Prompts go only to the selected provider; prompt and response bodies never go
  to Kafka or browser storage.
- Foundry calls set `store(false)`.
- Endpoints, raw deployment/model names, credentials, tenant IDs, subscription
  IDs, and customer identifiers are excluded from telemetry.
- Route and model data use bounded safe aliases from a hardcoded mapping. An
  unknown Foundry model is reported as `other`, not passed through.
- `MAX_CLOUD_REQUESTS_PER_RUN` caps Foundry calls. It does not apply to the
  simulator or Ollama.

## Known limitations

- The automated suite covers the deterministic simulator and provider adapter
  behavior, but this repository has not verified a live Microsoft Foundry
  project or paid Model Router deployment.
- The comparison is an observability demo, not a statistically controlled model
  quality or cost benchmark.
- Router mode cannot be changed at request time; preconfigure separate router
  deployments if a presentation must compare Balanced, Cost, and Quality.
- This version does not opt in to Conversations/long-term memory, agent tools,
  Azure AI Search RAG, managed evaluation, or managed tracing.
