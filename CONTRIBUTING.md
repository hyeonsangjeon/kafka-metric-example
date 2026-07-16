# Contributing

Foundry Stream Lab favors small, reproducible changes that keep the simulated
mode fully functional without cloud credentials.

## Before opening a pull request

1. Run `make test`.
2. Confirm `docker compose up --build` starts a healthy baseline run.
3. Do not commit `.env`, access tokens, endpoints, prompt bodies, responses, or
   captured customer data.
4. Update the event schema and fixtures together when changing the telemetry
   contract.
5. Include a screenshot for visible dashboard changes.

## Design invariants

- Consumer slowdown may increase lag and freshness, but not AI latency.
- Model throttling may increase retries and AI latency, but not Kafka lag.
- Duplicate delivery may increase raw records, but not completed requests.
- Hot partition must appear as skew, not as fabricated request failures.

Please use conventional, intent-focused commit messages and keep unrelated
refactors out of feature pull requests.
