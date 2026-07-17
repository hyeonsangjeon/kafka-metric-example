# Compare Run API input/output example

The released `v1.2.0` application received this public synthetic input:

```json
{
  "workload": "chat",
  "scenario": "healthy",
  "traffic": 1,
  "prompt": "Explain one practical Kafka reliability signal in two concise sentences."
}
```

It returned a normalized receipt with three planned requests and a hard limit
of ten provider invocations. The completed public result reports:

| Profile | Result | Selected model | P95 | Tokens in/out | Delta vs fixed |
| --- | --- | --- | ---: | ---: | --- |
| Fixed | 1/1, 0 failed | GPT-5.4 mini | 5,207 ms | 26 / 46 | baseline |
| Default Balanced | 1/1, 0 failed | GPT-5 mini | 5,210 ms | 32 / 471 | +3 ms, +0.1% |
| Advanced Cost | 1/1, 0 failed | GPT-5.4 nano | 3,105 ms | 32 / 58 | −2,102 ms, −40.4% |

The exact allowlisted files are in [`data/application`](../data/application):

- `comparison-input.json`
- `comparison-receipt.json`
- `comparison-result.json`
- `configuration-summary.json`
- `health-summary.json`
- `capture-metadata.json`

Comparison and child-run handles are normalized. Prompt and provider output
bodies, hashes, trace IDs, endpoints, deployment names, and cloud locators are
not part of the application result.
