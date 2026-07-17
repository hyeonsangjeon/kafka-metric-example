# Compare Run

Compare Run sends one unchanged workload through every comparison-capable
execution profile and places the results in one privacy-safe view. It is a
bounded sequential benchmark for demonstrations and integration checks, not a
statistically controlled production experiment.

## Execution model

The gateway owns one logical comparison from start to finish. It keeps the
normal single-run gate locked while it runs the provider's profiles in their
published order:

```text
fixed → router default / Balanced → router advanced / Cost or Quality
```

Each phase still emits a normal telemetry-v1 child run, so Kafka, trace, route,
and delivery behavior remain observable with the existing tooling. The gateway
does not start the next phase until every request in the current phase is
terminal. A normal run and a comparison therefore cannot overlap.

Sequential execution makes the result reproducible and keeps paid calls
bounded, but it does not remove time-of-day, provider-load, model-version, or
router-selection variance. Treat latency and model-mix deltas as observations
for this input, not universal performance claims.

## Provider support

| Provider | Comparison behavior |
| --- | --- |
| Simulator | Fixed, default-router, and advanced-router phases with deterministic synthetic routing |
| Ollama | Unavailable because Ollama exposes one real fixed profile; router results are never fabricated |
| Microsoft Foundry | Fixed, default Balanced router, and advanced router when all deployments are configured |

The browser uses the capability returned by `/api/v1/config`. An unavailable
comparison remains visible with an explanation; single-profile runs continue
to work.

## Provider-call budget

`traffic` means requests **per profile**. Before scheduling anything, the
gateway checks:

```text
traffic per profile × profile count <= provider invocation limit
```

The provider invocation limit is `MAX_CLOUD_REQUESTS_PER_RUN`. Every real
provider entry, including a retry, consumes one slot. First attempts for phases
that have not started are reserved, so a retry in Fixed cannot starve the
Default or Advanced phase. A request that cannot retry inside the remaining
budget ends with a safe failure code instead of exceeding the cap.

Stopping a comparison prevents new phases and retries. A request already sent
to a remote provider might still complete or incur usage; its late result is
ignored by the comparison projection.

## Result contract

The result contains only comparison and phase status, requested/completed/
failed/retry counts, success rate, p50/p95 latency, token totals and completeness,
safe model-family distribution, the fixed baseline deltas, child run IDs, and
provider-call budget use.

It deliberately excludes:

- prompt and response bodies;
- prompt/response hashes and trace IDs;
- Foundry endpoint, deployment, subscription, tenant, principal, or resource IDs;
- Ollama endpoint and raw local model name; and
- browser persistence such as `localStorage`.

`Export JSON` fetches the same bounded server projection and creates a local
file in the browser. It does not expand the data boundary or trigger another
model call.

## UI flow

1. Select a workload and failure scenario.
2. Set traffic per profile within the server-provided comparison limit.
3. Optionally enter one public/synthetic prompt.
4. Select **Compare profiles**.
5. Follow the fixed → default → advanced phase strip.
6. Compare request outcomes, p50/p95, retries, tokens, and model mix against
   the Fixed baseline.
7. Export the sanitized JSON when an evidence artifact is needed.

Keep the same input when comparing separate sessions. Do not interpret a Cost
router label as measured currency savings; cost is not calculated by this
application.
