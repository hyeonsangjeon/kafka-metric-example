# Live Foundry smoke and tracing

This tool calls the fixed deployment, the default Model Router deployment, and
the advanced Model Router deployment through the Foundry Responses API. It uses
`DefaultAzureCredential`, fetches the project's connected Application Insights
configuration, and exports OpenTelemetry spans to Azure Monitor.

Prompt and response content capture is explicitly disabled. The JSON output is
limited to the requested profile, privacy-safe operational measurements, the
underlying model name returned by Foundry, and the W3C trace ID. Use only
non-sensitive prompts even with content capture disabled.

```bash
az login

export FOUNDRY_PROJECT_ENDPOINT='https://RESOURCE.services.ai.azure.com/api/projects/PROJECT'
export FOUNDRY_MODEL='gpt-5.4-mini'
export FOUNDRY_ROUTER_DEFAULT_MODEL='model-router-default'
export FOUNDRY_ROUTER_ADVANCED_MODEL='model-router-cost'

uv run --python 3.12 \
  --with-requirements tools/foundry/requirements.txt \
  tools/foundry/live_smoke.py
```

Application Insights ingestion is asynchronous. Query Azure Monitor after two
to five minutes using one of the emitted trace IDs. Never enable
`OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT` for real customer data.
