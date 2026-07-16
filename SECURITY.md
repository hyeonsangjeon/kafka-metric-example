# Security policy

## Reporting a vulnerability

Please use GitHub's **Report a vulnerability** workflow in the repository's
Security tab. Do not open a public issue containing credentials, tenant details,
prompt content, or exploit instructions.

## Data boundary

Foundry Stream Lab publishes telemetry metadata only. Raw prompts, model
responses, access tokens, project endpoints, tenant IDs, subscription IDs, and
deployment names must never be written to Kafka events or browser storage.

The default `simulated` mode makes no cloud calls. Live Foundry mode uses
`DefaultAzureCredential`; API keys are intentionally unsupported.

## Scope

The API and development server bind to `127.0.0.1` by default. The container
opts into `0.0.0.0` internally while Compose publishes it on loopback only.

The included Kafka configuration is for a loopback-bound local lab. It has no
TLS, SASL, authorization, or durable storage and must not be exposed as a
production broker.
