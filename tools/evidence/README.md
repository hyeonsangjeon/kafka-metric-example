# Comparison evidence capture

`capture_comparison.sh` records the public, reproducible part of a completed
Compare Run before a disposable provider environment is deleted.

```bash
./tools/evidence/capture_comparison.sh \
  docs/evidence/YYYY-MM-DD-foundry-compare/data/application
```

The target directory must not exist. The application must be reachable on the
loopback-only `BASE_URL`. By default `/api/v1/config` must report Foundry mode
with exactly the fixed, default-router, and advanced-router profiles. Set
`EXPECTED_PROVIDER_MODE=simulated` only for a credential-free script rehearsal.
Traffic is checked against the server's `maxTrafficPerProfile` before the POST,
and only a completed comparison is publishable.
Foundry captures also require `SOURCE_VERSION`, the full lowercase
`SOURCE_COMMIT`, and `SOURCE_ARTIFACT` in the form `git:vX.Y.Z` or an immutable
`ghcr.io/...@sha256:...` digest. The tool rejects a completed run unless all three phases have zero
failures, complete token samples, and a non-empty model mix.

The capture retains:

- the exact public/synthetic input;
- safe health and configuration summaries;
- a normalized receipt and completed comparison result; and
- capture provenance and privacy assertions.

It never writes a raw snapshot, provider output, trace, hash, endpoint,
deployment, subscription, tenant, principal, or resource ID. Session-local
comparison and child-run handles are normalized. The JSON export is only one
part of the evidence gate: add reviewed screenshots, infrastructure inventory,
usage/tracing summaries, checksums, and the cleanup record before publishing a
live bundle.

Publish in two stages: first merge the complete pre-deletion bundle; then purge
the disposable resources, record the sanitized absence checks, regenerate
checksums, and merge the cleanup record separately.
