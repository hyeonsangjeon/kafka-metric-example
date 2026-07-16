#!/usr/bin/env python3
"""Run privacy-safe live Foundry calls and export their traces to Azure Monitor."""

from __future__ import annotations

import argparse
import json
import os
import time
from dataclasses import asdict, dataclass

# These flags must be set before importing the Foundry instrumentation package.
os.environ.setdefault("AZURE_EXPERIMENTAL_ENABLE_GENAI_TRACING", "true")
os.environ.setdefault("OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT", "false")
os.environ.setdefault("AZURE_TRACING_GEN_AI_TRACE_CONTEXT_PROPAGATION_INCLUDE_BAGGAGE", "false")

from azure.ai.projects import AIProjectClient
from azure.ai.projects.telemetry import AIProjectInstrumentor
from azure.identity import DefaultAzureCredential
from azure.monitor.opentelemetry import configure_azure_monitor
from opentelemetry import trace


@dataclass(frozen=True)
class SmokeResult:
    requested_profile: str
    selected_model: str
    latency_ms: int
    input_tokens: int | None
    output_tokens: int | None
    trace_id: str


def require_env(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise SystemExit(f"{name} is required")
    return value


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Call fixed/default/advanced Foundry deployments with content capture disabled."
    )
    parser.add_argument(
        "--prompt",
        default="In one sentence, explain why Kafka consumer lag is an operational signal.",
        help="A non-sensitive smoke-test prompt. The prompt is never printed.",
    )
    parser.add_argument("--max-output-tokens", type=int, default=128)
    return parser.parse_args()


def main() -> None:
    args = arguments()
    if not 16 <= args.max_output_tokens <= 512:
        raise SystemExit("--max-output-tokens must be between 16 and 512")

    endpoint = require_env("FOUNDRY_PROJECT_ENDPOINT")
    deployments = (
        ("fixed", require_env("FOUNDRY_MODEL")),
        ("router-default", require_env("FOUNDRY_ROUTER_DEFAULT_MODEL")),
        ("router-advanced", require_env("FOUNDRY_ROUTER_ADVANCED_MODEL")),
    )

    with DefaultAzureCredential(exclude_interactive_browser_credential=True) as credential:
        with AIProjectClient(endpoint=endpoint, credential=credential) as project:
            connection_string = (
                project.telemetry.get_application_insights_connection_string()
            )
            configure_azure_monitor(
                connection_string=connection_string,
                enable_live_metrics=False,
                resource_attributes={"service.name": "foundry-stream-lab-live-smoke"},
            )
            AIProjectInstrumentor().instrument(
                enable_content_recording=False,
                enable_trace_context_propagation=True,
                enable_baggage_propagation=False,
            )

            tracer = trace.get_tracer("foundry-stream-lab.live-smoke")
            results: list[SmokeResult] = []
            with project.get_openai_client() as openai:
                for profile, deployment in deployments:
                    started = time.perf_counter()
                    with tracer.start_as_current_span(
                        "foundry.stream-lab.model-router-smoke"
                    ) as span:
                        span.set_attribute("foundry.stream_lab.profile", profile)
                        response = openai.responses.create(
                            model=deployment,
                            input=args.prompt,
                            max_output_tokens=args.max_output_tokens,
                            store=False,
                        )
                        context = span.get_span_context()
                        usage = response.usage
                        results.append(
                            SmokeResult(
                                requested_profile=profile,
                                selected_model=str(response.model),
                                latency_ms=round(
                                    (time.perf_counter() - started) * 1000
                                ),
                                input_tokens=(
                                    usage.input_tokens if usage is not None else None
                                ),
                                output_tokens=(
                                    usage.output_tokens if usage is not None else None
                                ),
                                trace_id=f"{context.trace_id:032x}",
                            )
                        )

            provider = trace.get_tracer_provider()
            force_flush = getattr(provider, "force_flush", None)
            if callable(force_flush):
                force_flush(timeout_millis=30_000)

    print(json.dumps([asdict(result) for result in results], indent=2))


if __name__ == "__main__":
    main()
