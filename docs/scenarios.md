# Model routing and reliability scenarios

All scenarios are deterministic in simulated mode and apply to one run only.

## Fixed vs router comparison

This is the primary demo. Change only the execution profile so the request and
telemetry paths remain comparable.

### Credential-free rehearsal

1. Start the default simulator and choose the **Chat** workload, **Healthy**
   scenario, traffic `20`, and one short prompt.
2. Select `fixed`, run it, and note completed/failed outcomes, p95 latency,
   token counts, and the `fixed` selected route.
3. Keep every input unchanged, select `router-balanced`, and run again.
4. Inspect the routing panel and trace drawer. The simulator should show a
   deterministic mix of `fast`, `general`, and `reasoning` safe route labels.
5. State explicitly that these routing decisions are synthetic. They rehearse
   the dashboard and Kafka story without claiming Foundry behavior.

### Foundry presentation

1. Configure both `FOUNDRY_MODEL` and `FOUNDRY_ROUTER_MODEL`; configure the
   Model Router deployment itself for the intended Balanced, Cost, or Quality
   mode.
2. Set `FOUNDRY_ROUTER_PROFILE` to matching metadata and keep traffic within
   `MAX_CLOUD_REQUESTS_PER_RUN`.
3. Run `fixed`, then `router-balanced`, using the same workload, scenario,
   traffic, and prompt.
4. Compare outcomes, p95 latency, attempts, token counts, and the distribution
   of privacy-safe model-family labels. Do not interpret the short sample as a
   quality or cost benchmark.

Ollama exposes only `ollama-fixed`; it is useful for a real local-model request
path, but it does not emulate Foundry Model Router. An Ollama failure never
falls back to Foundry.

## Healthy baseline

Requests use their trace ID as the Kafka key and distribute across partitions.
Use this run to establish normal request latency and telemetry freshness.

## Model throttling

Selected provider attempts emit a synthetic rate-limit signal and retry once
within a bounded policy. This changes provider tail latency and attempts
without pausing the Kafka consumer.

## Consumer slowdown

The consumer deliberately delays projection while the request path continues.
End offsets advance faster than consumed offsets and telemetry freshness grows.
AI completion rate and provider latency must remain stable.

## Duplicate delivery

The producer writes the same envelope and `event_id` at a second offset. The
projector records the duplicate observation but applies the logical event once.

## Hot partition

Most events use the same routing key. One lane accumulates a disproportionate
share of records while requests continue to complete normally.

## Demo sequence

After the fixed/router comparison:

1. Keep `router-balanced` and run **Consumer slowdown**. Watch lag and freshness
   rise independently of provider latency.
2. Run **Duplicate delivery** and confirm completed requests do not increase
   twice.
3. Run **Model throttling** and compare retries/tail latency with stable lag.
4. Run **Hot partition** and inspect lane skew without fabricated model errors.
