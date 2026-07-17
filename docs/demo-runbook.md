# Demo runbook

This runbook reproduces the Foundry Stream Lab from a clean local checkout,
through the simulator or Ollama, and optionally through newly provisioned
Microsoft Foundry resources. The original 2026-07-16 Azure environment was a
disposable demo; its preserved results and cleanup state are in
[the evidence bundle](evidence/2026-07-16-foundry-live/README.md).

## 1. Choose a provider boundary

| Path | Credentials | Profiles | Paid cloud calls |
| --- | --- | --- | --- |
| Simulator | None | `fixed`, `router-default`, `router-advanced` | No |
| Ollama | None | `ollama-fixed` | No |
| Foundry | Microsoft Entra | `fixed`, `router-default`, `router-advanced` | Yes |

There is no automatic provider fallback. Ollama failure never turns into a
Foundry call, and the default Docker configuration reads no Azure credentials.

## 2. Credential-free simulator demo

Start Kafka, topic initialization, and the application:

```bash
docker compose up --build
```

Open `http://127.0.0.1:8080`, then use this repeatable sequence:

1. Reset the session.
2. Select **Chat**, **Healthy**, and traffic `3` per profile.
3. Select **Compare profiles**. The gateway runs Fixed, Default Balanced, then
   Advanced Cost with the same input and no overlap.
4. Review the three result cards and export the privacy-safe JSON.
5. For a larger single-profile routing mix, run the Default profile separately
   with traffic `20` after the comparison completes.
6. Reset and run **Model throttling** with traffic `9` to show one retry. The
   cloud provider-call cap does not apply to a standalone simulator run.
7. Reset and run **Duplicate delivery** with traffic `20`; five duplicates are
   dropped without double-counting outcomes.

The simulator routing mix is a teaching aid, not a local implementation of
Microsoft Model Router.

## 3. Ollama fixed-model demo

Pull a model in Ollama first, start Kafka, and run the gateway on the host:

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

In another terminal:

```bash
cd web
npm ci
npm run dev
```

Open `http://127.0.0.1:5173`. Ollama exposes only `ollama-fixed`; router
profiles remain absent rather than being simulated or sent to the cloud.

## 4. Provision a disposable Foundry demo

Use a non-production subscription and a globally unique lowercase suffix:

```bash
az login
export AZURE_UNIQUE_SUFFIX='your-unique-suffix'

./infra/provision.sh --dry-run
./infra/provision.sh --apply
./infra/verify.sh
```

The plan creates a fixed GPT-5.4 mini deployment, a default Balanced Model
Router, a Cost router restricted to GPT-5.4 nano/mini/full, Application
Insights, Log Analytics, the project monitoring connection, and scoped RBAC.
Local/key auth stays disabled.

Run Kafka and the Foundry gateway on the host:

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
MAX_CLOUD_REQUESTS_PER_RUN=3 \
java -jar target/foundry-stream-lab.jar
```

Start Vite from `web/` and open `http://127.0.0.1:5173`.

## 5. Fixed/default/advanced presenter flow

Use one public prompt and keep every other input unchanged:

```text
Explain one practical Kafka reliability signal.
```

1. Reset the session.
2. Select **Chat**, **Healthy**, traffic `1` per profile, and the public prompt.
3. Select **Compare profiles** once.
4. Wait for Fixed → Default → Advanced to complete in order.
5. Record status, selected route/model family, latency, input/output tokens,
   baseline deltas, Kafka lag, and the content-free comparison export.

Routing is observational. Do not promise which model a router will select. In
the preserved one-request run, fixed selected GPT-5.4 mini, default Balanced
selected GPT-5 mini, and advanced Cost selected GPT-5.4 nano.

The dashboard deliberately exposes telemetry output, not the AI response body.
See [the HTTP examples](api-examples.md) for the exact contract.

## 6. Managed tracing and evaluation

Run the content-redacted tracing smoke tool after exporting the same project
endpoint and deployment names:

```bash
export FOUNDRY_PROJECT_ENDPOINT='https://YOUR-RESOURCE.services.ai.azure.com/api/projects/YOUR-PROJECT'
export FOUNDRY_MODEL='YOUR-FIXED-DEPLOYMENT'
export FOUNDRY_ROUTER_DEFAULT_MODEL='YOUR-DEFAULT-ROUTER'
export FOUNDRY_ROUTER_ADVANCED_MODEL='YOUR-ADVANCED-ROUTER'

uv run --python 3.12 \
  --with-requirements tools/foundry/requirements.txt \
  tools/foundry/live_smoke.py
```

This tool, rather than the Java application as a whole, installs the managed
OpenTelemetry integration. Prompt/response capture and baggage are disabled.

Run the pinned local matrix and optionally submit the precomputed results to
Foundry Evaluation:

```bash
export AZURE_MODEL_ROUTER_ENDPOINT='https://YOUR-RESOURCE.services.ai.azure.com'
export AZURE_OPENAI_ENDPOINT='https://YOUR-RESOURCE.services.ai.azure.com'
export AZURE_MODEL_ROUTER_BASIC_DEPLOYMENT='YOUR-DEFAULT-ROUTER'
export AZURE_MODEL_ROUTER_ADVANCED_DEPLOYMENT='YOUR-ADVANCED-ROUTER'
export AZURE_BASELINE_DEPLOYMENT='YOUR-FIXED-DEPLOYMENT'
export AZURE_JUDGE_DEPLOYMENT='YOUR-FIXED-DEPLOYMENT'

python3 tools/eval/eval.py validate --remote-pin
python3 tools/eval/eval.py bootstrap
python3 tools/eval/eval.py matrix --auth entra

export AZURE_AI_PROJECT_ENDPOINT='https://YOUR-RESOURCE.services.ai.azure.com/api/projects/YOUR-PROJECT'
export AZURE_AI_MODEL_DEPLOYMENT_NAME='YOUR-FIXED-DEPLOYMENT'
export MATRIX_DIRECTORY='matrix-YYYYMMDDTHHMMSSZ-ID'

python3 tools/eval/eval.py cloud-eval \
  --input-dir "tools/eval/results/$MATRIX_DIRECTORY/basic"
python3 tools/eval/eval.py cloud-eval \
  --input-dir "tools/eval/results/$MATRIX_DIRECTORY/advanced"
```

Replace `MATRIX_DIRECTORY` with the directory printed by the completed matrix
command. Managed evaluation consumes judge tokens; use `--dry-run` first when
validating a new result directory.

Keep `tools/eval/results/` ignored. It contains bulk model responses and cloud
locators. Export only manually reviewed synthetic examples, charts, and
sanitized aggregates.

## 7. Evidence gate and cleanup

Before deletion:

1. Run the released application and capture its allowlisted comparison result:

   ```bash
   export SOURCE_VERSION='v1.2.0'
   export SOURCE_COMMIT="$(git rev-parse 'v1.2.0^{commit}')"
   export SOURCE_ARTIFACT='ghcr.io/hyeonsangjeon/kafka-metric-example@sha256:RELEASE_DIGEST'
   ./tools/evidence/capture_comparison.sh \
     docs/evidence/YYYY-MM-DD-foundry-compare/data/application
   ```

2. Capture the resource/deployment inventory without identity or resource IDs.
3. Export dashboard, evaluation, tracing, usage, and cost-delay summaries.
4. Capture desktop and mobile screenshots and record the release tag, commit,
   container digest, host architecture, and tool versions in the manifest.
5. Run `./infra/verify.sh` and the cleanup dry-run.
6. Generate and verify checksums, then scan for credentials, IDs, endpoint URLs,
   absolute local paths, and raw payloads.
7. Commit, push, review, and merge this pre-deletion evidence. Do not delete the
   Azure resource group until the merge is visible on the default branch.

Then delete the dedicated resource group and release the Foundry account name:

```bash
./infra/cleanup.sh --dry-run --purge
./infra/cleanup.sh \
  --apply \
  --purge \
  --confirm "PURGE-${AZURE_FOUNDRY_ACCOUNT:-aif-kafka-router-$AZURE_UNIQUE_SUFFIX}"
```

If provisioning used name overrides, export the same overrides before every
verify or cleanup command. After cleanup, confirm the resource group is absent
and the account is no longer in the soft-deleted list. Subscription-level
provider registrations intentionally remain. Record those read-only checks in a
sanitized cleanup summary, update the evidence README/manifest, regenerate and
verify checksums, scan again, then commit, review, and merge this second cleanup
record. The two merges make the evidence durable before irreversible purge while
still proving the purge afterward.
