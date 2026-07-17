# HTTP input/output examples

The API returns provider and delivery telemetry. It intentionally does not
return or retain an AI response body. All IDs and timestamps below are
illustrative unless marked as an observed aggregate.

## Health and safe configuration

```bash
curl -sS http://127.0.0.1:8080/api/v1/health | jq
curl -sS http://127.0.0.1:8080/api/v1/config | jq
```

Foundry mode exposes these profiles when all three deployments are configured.
This is a sanitized excerpt; the full response also contains API version,
labels/descriptions, safe limits, workload/scenario options, and defaults:

```json
{
  "mode": "foundry",
  "transport": "kafka",
  "cloudReady": true,
  "modelProfiles": [
    {"id": "fixed", "strategy": "fixed", "router": false},
    {"id": "router-default", "strategy": "balanced", "router": true},
    {"id": "router-advanced", "strategy": "cost", "router": true}
  ],
  "comparison": {
    "available": true,
    "unavailableReason": null,
    "profiles": [
      {"id": "fixed", "kind": "fixed", "routeStrategy": "fixed"},
      {"id": "router-default", "kind": "router", "routeStrategy": "balanced"},
      {"id": "router-advanced", "kind": "router", "routeStrategy": "cost"}
    ],
    "profileCount": 3,
    "trafficSemantics": "per-profile",
    "defaultTrafficPerProfile": 3,
    "maxTrafficPerProfile": 3,
    "providerInvocationLimit": 10
  }
}
```

No endpoint, tenant, subscription, credential, or raw deployment identifier is
returned by this surface.

## Start one bounded run

Omit `prompt` to use the built-in public prompt for the selected workload:

```bash
curl -i -sS \
  -H 'Content-Type: application/json' \
  -d '{
    "workload": "chat",
    "scenario": "healthy",
    "traffic": 1,
    "modelProfile": "router-default"
  }' \
  http://127.0.0.1:8080/api/v1/runs
```

Accepted response (`202`):

```json
{
  "runId": "00000000-0000-4000-8000-000000000001",
  "status": "running",
  "modelProfile": "router-default"
}
```

The UUID is an opaque session-local handle. The UI submits matching `modelId`
and `modelProfile` values for backward compatibility; direct API clients may
send only `modelProfile`.

## Compare every available profile

`traffic` is requests per profile. The gateway rejects the request before any
provider call when `traffic × profiles` is greater than the configured total
provider-invocation limit. The request intentionally does not accept
`modelProfile` because the server freezes the provider's available profile set.

```bash
curl -i -sS \
  -H 'Content-Type: application/json' \
  -d '{
    "workload": "chat",
    "scenario": "healthy",
    "traffic": 1,
    "prompt": "Explain one practical Kafka reliability signal."
  }' \
  http://127.0.0.1:8080/api/v1/comparisons
```

Accepted response (`202`):

```json
{
  "comparisonId": "00000000-0000-4000-8000-000000000010",
  "status": "running",
  "profiles": ["fixed", "router-default", "router-advanced"],
  "trafficPerProfile": 1,
  "plannedRequests": 3,
  "providerInvocationLimit": 10
}
```

Poll the safe export until it is terminal:

```bash
curl -sS \
  http://127.0.0.1:8080/api/v1/comparisons/00000000-0000-4000-8000-000000000010 \
  | jq
```

Sanitized completed excerpt:

```json
{
  "comparisonId": "00000000-0000-4000-8000-000000000010",
  "status": "completed",
  "workload": "chat",
  "scenario": "healthy",
  "trafficPerProfile": 1,
  "profiles": ["fixed", "router-default", "router-advanced"],
  "currentProfile": null,
  "plannedRequests": 3,
  "providerInvocations": 3,
  "providerInvocationLimit": 10,
  "phases": [
    {
      "profile": "fixed",
      "label": "Fixed deployment",
      "kind": "fixed",
      "routeStrategy": "fixed",
      "status": "completed",
      "requested": 1,
      "completed": 1,
      "failed": 0,
      "retries": 0,
      "successRate": 100.0,
      "p50LatencyMs": 3062,
      "p95LatencyMs": 3062,
      "inputTokens": 22,
      "outputTokens": 115,
      "tokenSamples": 1,
      "models": [
        {"modelFamily": "GPT-5.4 mini", "count": 1, "percentage": 100.0}
      ],
      "p95DeltaMs": 0,
      "p95DeltaPercent": 0.0,
      "totalTokensDelta": 0,
      "totalTokensDeltaPercent": 0.0
    }
  ]
}
```

The other phase objects have the same allowlisted shape and include deltas from
Fixed. The export contains no prompt/response body, hash, trace ID, endpoint,
raw deployment name, tenant, subscription, principal, or resource ID. Stop an
active comparison with `POST /api/v1/comparisons/{id}/stop`; already-sent
remote requests may still incur usage, but late results are ignored.

Comparison request errors use the normal error envelope:

| HTTP | Code | Meaning |
| --- | --- | --- |
| `422` | `COMPARISON_UNAVAILABLE` | The provider exposes fewer than two profiles. |
| `422` | `COMPARISON_BUDGET_EXCEEDED` | Traffic × profile count exceeds the total provider-call limit. |
| `409` | `EXECUTION_ALREADY_ACTIVE` | A normal run or comparison already owns the execution gate. |
| `422` | `INVALID_COMPARISON_REQUEST` | JSON, workload, scenario, traffic, prompt, or forbidden profile selection is invalid. |

```json
{
  "error": {
    "code": "COMPARISON_BUDGET_EXCEEDED",
    "message": "traffic multiplied by profile count exceeds the provider invocation limit of 10"
  }
}
```

## Completed projection

Poll `GET /api/v1/snapshot` until `lastRun.status` is `completed`. This sanitized
excerpt mirrors the observed 2026-07-16 Default Balanced run:

```json
{
  "lastRun": {
    "runId": "00000000-0000-4000-8000-000000000001",
    "workload": "chat",
    "scenario": "healthy",
    "traffic": 1,
    "status": "completed",
    "completedRequests": 1,
    "modelProfile": "router-default",
    "routeStrategy": "balanced"
  },
  "kpis": {
    "requests": 1,
    "completed": 1,
    "failed": 0,
    "inputChars": 47,
    "outputChars": 1936,
    "inputTokens": 28,
    "outputTokens": 1209,
    "p95LatencyMs": 10959,
    "consumerLag": 0
  },
  "routing": {
    "profileId": "router-default",
    "strategy": "balanced",
    "totalRequests": 1,
    "routes": [
      {
        "id": "gpt-5-mini",
        "label": "GPT-5 mini",
        "requests": 1,
        "share": 100.0,
        "p95LatencyMs": 10959,
        "successRate": 100.0
      }
    ]
  }
}
```

`outputChars` and `outputTokens` describe the response without copying it into
Kafka, the projection, or browser storage.

## Telemetry event and SSE

`GET /api/v1/stream` begins with `event: snapshot`, then emits a `telemetry`
event for each accepted unique Kafka record. Every delivery refreshes the
snapshot, including a duplicate that the idempotent projection drops. A
sanitized, schema-valid `RESPONSE_COMPLETED` envelope looks like this:

```text
event: telemetry
data: {"schema_version":"1.0","event_id":"00000000-0000-4000-8000-000000000002","event_type":"RESPONSE_COMPLETED","session_id":"00000000-0000-4000-8000-000000000003","run_id":"00000000-0000-4000-8000-000000000001","trace_id":"00000000-0000-4000-8000-000000000004","emitted_at":"2026-07-16T16:43:01Z","provider_alias":"foundry","workload":"chat","scenario":"healthy","sequence":2,"details":{"prompt_hash":"sha256:f2a21c468b95fdf0259ac96714a6b6f7112bc6d558a878157a422d0ef1913f2e","prompt_chars":47,"response_hash":"sha256:0000000000000000000000000000000000000000000000000000000000000000","response_chars":1936,"latency_ms":10959,"attempt":1,"synthetic":false,"model_profile":"router-default","route_strategy":"balanced","selected_route":"gpt-5-mini","model_family":"GPT-5 mini","input_tokens":28,"output_tokens":1209}}
```

The prompt hash is reproducible here because the prompt is public. The all-zero
response hash is an explicitly illustrative, schema-valid replacement. Do not
treat hashing as anonymization for private or low-entropy prompts.

## Validation error

When both compatibility fields are supplied, they must match:

```bash
curl -i -sS \
  -H 'Content-Type: application/json' \
  -d '{
    "workload": "chat",
    "traffic": 1,
    "modelId": "fixed",
    "modelProfile": "router-default"
  }' \
  http://127.0.0.1:8080/api/v1/runs
```

Response (`422`):

```json
{
  "error": {
    "code": "INVALID_RUN_REQUEST",
    "message": "modelId and modelProfile must match when both are provided"
  }
}
```

## Reset ephemeral state

```bash
curl -i -X DELETE http://127.0.0.1:8080/api/v1/session
```

The response is `204 No Content`. It stops new scheduling and clears the
in-memory projection; already in-flight provider calls may still complete and
incur usage, although their telemetry is discarded. It does not delete Kafka
topic history or cloud resources.
