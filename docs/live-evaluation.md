# Live Model Router evaluation — 2026-07-16

This record contains only aggregate, non-response results from a live East US 2
Microsoft Foundry run. The synthetic prompt dataset is committed for
reproducibility; raw model responses, per-request result records, request IDs,
and cloud account identifiers remain in the ignored local
`tools/eval/results/` directory and are not committed.

## Method

- Microsoft's Model Router Auto Evaluation toolkit was pinned to commit
  `ecf0aa26b4b613536a2ecb23ac1231b380c064f1` and tree
  `599225a9e437e016044aa75fd8d8d60d09205b49`.
- The same 12 synthetic Kafka prompts were sent to each router and a fixed
  GPT-5.4 mini baseline.
- Requests were serialized and transient failures were retried up to five times.
- GPT-5.4 mini judged each successful pair in both response orders and scored
  each response independently.
- Authentication used Microsoft Entra ID. Account keys remained disabled.
- Cost was marked N/A because the pinned toolkit has no verified prices for all
  current GPT-5.4-era routes; allowing its prefix fallback would misprice them.

## Aggregate results

| Profile | Success | Router mean / p50 / p95 | Fixed mean / p50 / p95 | Pairwise result | Absolute score (router / fixed) |
| --- | --- | --- | --- | --- | --- |
| Default Balanced | 12/12 router, 12/12 fixed | 7.096 s / 6.486 s / 13.177 s | 3.562 s / 3.308 s / 5.958 s | 3 wins / 6 losses / 3 ties | 4.312 / 4.688 |
| Advanced Cost + GPT-5.4 subset | 12/12 router, 12/12 fixed | 5.388 s / 5.603 s / 9.047 s | 4.500 s / 3.582 s / 10.024 s | 1 win / 6 losses / 5 ties | 3.479 / 4.771 |

Default Balanced selected four families across the 12 prompts:

| Selected model | Count |
| --- | ---: |
| Grok 4.1 fast reasoning | 8 |
| GPT-5 mini | 2 |
| GPT-5.4 | 1 |
| GPT-5.4 mini | 1 |

The Advanced Cost deployment was restricted to GPT-5.4 nano, mini, and full:

| Selected model | Count |
| --- | ---: |
| GPT-5.4 nano | 11 |
| GPT-5.4 mini | 1 |
| GPT-5.4 | 0 |

## Foundry managed graders

Both precomputed result sets were also submitted to the project's Foundry
Evaluation service with three quality graders and the Model Router latency
grader. Cost grading remained disabled.

| Profile | Evaluated / errored | Overall criteria pass | Router absolute quality | Fixed absolute quality | Pairwise grader pass | Latency grader pass |
| --- | --- | --- | --- | --- | --- | --- |
| Default Balanced | 12 / 0 | 0/12 | 4.17 mean, 92% pass | 4.67 mean, 100% pass | 67% | 0% |
| Advanced Cost + subset | 12 / 0 | 2/12 | 3.42 mean, 67% pass | 4.58 mean, 100% pass | 50% | 33% |

“Evaluation completed” means the service processed every record without grader
errors; it does not mean the candidate met the configured acceptance criteria.
The low overall pass counts are therefore retained as a result, not treated as
an infrastructure failure.

## Interpretation

The run demonstrates that deployment-level routing policy materially changed
both model selection and the observed quality/latency trade-off. In this tiny
dataset, the Cost profile routed almost entirely to nano and reduced router p95
relative to the default Router run, but its judged quality was lower than the
fixed mini baseline.

These numbers are directional only. Each category has one prompt, the two
profiles were measured in separate runs, and transient service/cold-start effects
are visible. A preliminary concurrent run was discarded after Azure returned
transient 500 responses; the reported serialized runs completed with zero request
or judge errors. Use at least 100 representative production-like prompts before
making a routing or purchasing decision.
