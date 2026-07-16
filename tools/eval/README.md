# Kafka Model Router evaluation

This directory wraps Microsoft's official [Model Router Auto Evaluation toolkit](https://github.com/microsoft-foundry/Model-Router-Auto-Evaluation) for this Kafka demo. It runs the same prompt set against a Model Router deployment and one fixed baseline, then reports quality, latency, success rate, and selected-model distribution.

The wrapper pins toolkit commit [`ecf0aa26b4b613536a2ecb23ac1231b380c064f1`](https://github.com/microsoft-foundry/Model-Router-Auto-Evaluation/commit/ecf0aa26b4b613536a2ecb23ac1231b380c064f1) and tree `599225a9e437e016044aa75fd8d8d60d09205b49`. `verify-pin` rejects a different origin, commit, tree, or a dirty checkout.

Microsoft's methodology recommends at least 100 representative workload prompts for a statistically useful decision. The included 12-prompt Kafka dataset is intentionally a smoke/demo set; results from fewer than 30 prompts are directional only. See the [official evaluation walkthrough](https://devblogs.microsoft.com/foundry/how-to-run-evals-for-model-router/) and [Model Router documentation](https://learn.microsoft.com/azure/foundry/openai/how-to/model-router).

## Profiles

- `basic`: a default Balanced Model Router deployment using its full supported model set.
- `advanced`: a separately created deployment using a custom routing mode and/or model subset.
- Both profiles use exactly the same `AZURE_BASELINE_DEPLOYMENT`, judge, dataset, and toolkit revision.

The wrapper does not modify Azure deployments. Create the two router deployments first so their settings remain stable throughout the matrix run.

## Install and validate

Python 3.9-3.13, Git, and Azure CLI are required. Bootstrap clones the exact locked commit into ignored `.cache/` and creates an ignored `.venv/`. When `uv` is installed, bootstrap prefers its managed Python 3.12 so it is isolated from a broken or incompatible system Python; otherwise it uses `venv` with an installed supported interpreter. Python 3.14 is not used yet because the pinned toolkit dependency set has not been qualified for it. Set `EVAL_PYTHON=/path/to/python3.13` to select an interpreter explicitly.

The bootstrap also constrains Matplotlib to `>=3.7,<3.11`. The pinned toolkit still calls `matplotlib.cm.get_cmap`, which was removed in Matplotlib 3.11; bootstrap and validation verify both the installed version and that API before a run.

```bash
python3 tools/eval/eval.py validate --remote-pin
python3 tools/eval/eval.py bootstrap
python3 tools/eval/eval.py validate --official
```

`validate` is offline unless `--remote-pin` is supplied. `--official` invokes the pinned toolkit's dataset/config loading with placeholder values and makes no Azure API calls.

## Keyless live run (recommended)

The repo-local adapter adds `DefaultAzureCredential` to the pinned toolkit without changing its checkout. Sign in and export only non-secret endpoint and deployment identifiers:

```bash
az login

export AZURE_MODEL_ROUTER_ENDPOINT="https://YOUR_RESOURCE.services.ai.azure.com"
export AZURE_OPENAI_ENDPOINT="https://YOUR_RESOURCE.services.ai.azure.com"
export AZURE_MODEL_ROUTER_BASIC_DEPLOYMENT="model-router-basic"
export AZURE_MODEL_ROUTER_ADVANCED_DEPLOYMENT="model-router-advanced"
export AZURE_BASELINE_DEPLOYMENT="gpt-5-mini-fixed"
export AZURE_JUDGE_DEPLOYMENT="gpt-5-mini-judge"

python3 tools/eval/eval.py run --profile basic
python3 tools/eval/eval.py run --profile advanced
python3 tools/eval/eval.py matrix
```

`AZURE_JUDGE_ENDPOINT` is optional and defaults to `AZURE_OPENAI_ENDPOINT`. The signed-in identity needs inference access to all three deployments. Keep the baseline and judge fixed while comparing router profiles.

The pinned upstream toolkit still shows the retired `/models` endpoint in its example. This wrapper accepts that suffix for compatibility but removes it before constructing `AsyncAzureOpenAI`; prefer the resource-root endpoint shown above.

### Reliability and evaluation methodology

The wrapper overrides the pinned toolkit's local runtime defaults to one in-flight inference request, one in-flight judge request, and five attempts for both phases. The toolkit's exponential backoff remains active between attempts. These conservative settings avoid correlated transient 5xx failures across a small 12-prompt demo and make quality coverage more meaningful; they intentionally trade throughput for a cleaner paired comparison. Both `basic` and `advanced` runs use the same settings, recorded in `wrapper-provenance.json`.

For Azure GPT-5.4 deployments, the adapter translates only judge messages with the `system` role to the supported `developer` role before the SDK call. This addresses a deterministic Azure 500 observed for the GPT-5.4 `system`+`user` shape. Router and baseline evaluation prompts remain identical user-only messages, so the paired comparison is unchanged. Other model families retain the upstream roles.

### Cost status

Cost is deliberately emitted as `N/A` for this demo. The pinned toolkit does not contain verified prices for every model available to newer router deployments, and its prefix fallback can incorrectly price a newer GPT-5 variant as plain GPT-5. Local `results.json`, `report.md`, and provenance explicitly mark cost unavailable; managed Foundry evaluation disables only the cost grader. Quality, latency, success rate, and selected-model distribution remain valid. Re-enable cost only in a future reviewed change that supplies exact, dated rates for the fixed baseline and every possible router-selected model.

For subscriptions with local authentication disabled (`disableLocalAuth=true`), use this default `--auth entra` path. Do not enable resource keys just for the benchmark. The wrapper obtains bearer tokens for `https://cognitiveservices.azure.com/.default`; no token is persisted.

## API-key fallback

The upstream toolkit's native local client requires API keys. If Entra authentication is unavailable, pass keys only as temporary process environment variables and run with `--auth key`:

```bash
export AZURE_MODEL_ROUTER_KEY="..."
export AZURE_OPENAI_KEY="..."
export AZURE_JUDGE_KEY="..."  # optional when it is the baseline key

python3 tools/eval/eval.py matrix --auth key

unset AZURE_MODEL_ROUTER_KEY AZURE_OPENAI_KEY AZURE_JUDGE_KEY
```

Do not create `.env` files, paste keys into arguments, or commit shell transcripts. The wrapper never loads dotenv files and never prints secret values.

## Foundry managed evaluation

After a local run, submit the precomputed responses to Foundry's managed graders. This path is keyless and uses the official toolkit's `DefaultAzureCredential` integration:

```bash
export AZURE_AI_PROJECT_ENDPOINT="https://YOUR_RESOURCE.services.ai.azure.com/api/projects/YOUR_PROJECT"
export AZURE_AI_MODEL_DEPLOYMENT_NAME="gpt-5-mini-judge"

python3 tools/eval/eval.py cloud-eval \
  --input-dir tools/eval/results/basic/RUN_DIRECTORY \
  --dry-run

python3 tools/eval/eval.py cloud-eval \
  --input-dir tools/eval/results/basic/RUN_DIRECTORY
```

Cloud evaluation creates evaluation artifacts in the selected Foundry project and consumes judge tokens. Dry-run transforms and validates inputs without a cloud call. Because verified pricing is unavailable, the official quality and latency graders run while its cost grader is disabled.

## Results and data handling

All generated artifacts are confined to `tools/eval/results/`, which is ignored by the nested `.gitignore`. Each completed local run contains the official toolkit dashboard/report/result files plus `wrapper-provenance.json` with the profile, authentication mode, dataset, exact toolkit revision, and reliability settings.

Results include model responses and may contain sensitive workload content. Keep this directory local, apply normal telemetry retention controls, and delete it when no longer needed. The committed dataset contains synthetic Kafka prompts only and no endpoint, tenant, credential, or production payload.
