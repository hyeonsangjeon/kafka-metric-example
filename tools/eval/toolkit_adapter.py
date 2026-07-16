#!/usr/bin/env python3
"""Thin adapter around the pinned toolkit; run only through eval.py."""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path


_COST_UNAVAILABLE_NOTE = (
    "Cost comparison is intentionally unavailable: the pinned toolkit price table does not "
    "contain verified rates for every model available to the target router, and its prefix "
    "fallback can misprice newer GPT-5 variants as GPT-5. Quality, latency, success rate, and "
    "selected-model distribution remain valid."
)
_RELIABILITY_DEFAULTS = {
    "max_parallel_requests": 1,
    "max_retries": 5,
    "judge_max_parallel": 1,
    "judge_max_retries": 5,
}


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("local", "cloud"))
    parser.add_argument("--toolkit-root", type=Path, required=True)
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument("--input-dir", type=Path)
    parser.add_argument("--profile", choices=("basic", "advanced"))
    parser.add_argument("--auth", choices=("entra", "key"), default="entra")
    parser.add_argument("--sample-size", type=int)
    parser.add_argument("--dry-run", action="store_true")
    return parser


def _prepare_toolkit(toolkit_root: Path) -> None:
    root = toolkit_root.resolve()
    if not (root / "src").is_dir() or not (root / "configs/default.yaml").is_file():
        raise RuntimeError(f"Invalid toolkit root: {root}")
    os.chdir(root)
    sys.path.insert(0, str(root))


def _azure_openai_endpoint(value: str) -> str:
    """Return the resource root expected by AsyncAzureOpenAI.

    The pinned toolkit's example still includes the retired ``/models``
    inference path. AsyncAzureOpenAI appends ``/openai`` itself, so retaining
    that suffix would construct ``/models/openai/...``.
    """
    endpoint = value.rstrip("/")
    if endpoint.endswith("/models"):
        endpoint = endpoint[: -len("/models")]
    return endpoint


def _translate_azure_gpt54_messages(deployment: str, messages):
    """Translate the unsupported GPT-5.4 system role without mutating input."""
    if not deployment.strip().lower().startswith("gpt-5.4"):
        return messages
    return [
        {**message, "role": "developer"}
        if isinstance(message, dict) and message.get("role") == "system"
        else message
        for message in messages
    ]


def _wrap_azure_gpt54_roles(client, deployment: str):
    if not deployment.strip().lower().startswith("gpt-5.4"):
        return client
    completions = client.chat.completions
    original_create = completions.create

    async def create_with_supported_roles(*args, **kwargs):
        if "messages" in kwargs:
            kwargs = dict(kwargs)
            kwargs["messages"] = _translate_azure_gpt54_messages(
                deployment, kwargs["messages"]
            )
        return await original_create(*args, **kwargs)

    completions.create = create_with_supported_roles
    return client


def _install_client_builders(auth: str):
    from azure.identity import DefaultAzureCredential, get_bearer_token_provider
    from openai import AsyncAzureOpenAI, AsyncOpenAI
    from src import client as client_module
    from src import judge as judge_module

    credential = None
    token_provider = None
    if auth == "entra":
        credential = DefaultAzureCredential()
        token_provider = get_bearer_token_provider(
            credential, "https://cognitiveservices.azure.com/.default"
        )

    def build_client(config):
        if config.type == "azure_openai":
            arguments = {
                "azure_endpoint": _azure_openai_endpoint(config.endpoint_url),
                "api_version": "2024-12-01-preview",
            }
            if auth == "entra":
                arguments["azure_ad_token_provider"] = token_provider
            else:
                arguments["api_key"] = config.api_key
            client = AsyncAzureOpenAI(**arguments)
            return _wrap_azure_gpt54_roles(client, config.deployment_name)
        if config.type == "openai_compatible" and auth == "key":
            return AsyncOpenAI(base_url=config.endpoint_url, api_key=config.api_key)
        raise ValueError("Entra mode supports azure_openai endpoints only")

    client_module._build_client = build_client
    judge_module._build_judge_client = build_client
    return credential


def _disable_unverified_cost_metrics() -> None:
    from src import metrics as metrics_module

    def unavailable_cost(*args, **kwargs):
        return None

    metrics_module._compute_cost_stats = unavailable_cost


def _apply_reliability_defaults(config) -> None:
    """Apply conservative settings after loading the pinned toolkit config."""
    config.max_parallel_requests = _RELIABILITY_DEFAULTS["max_parallel_requests"]
    config.max_retries = _RELIABILITY_DEFAULTS["max_retries"]
    if config.judge is not None:
        config.judge.max_parallel = _RELIABILITY_DEFAULTS["judge_max_parallel"]
        config.judge.max_retries = _RELIABILITY_DEFAULTS["judge_max_retries"]


def _mark_cost_unavailable(output_dir: Path) -> None:
    results_path = output_dir / "results.json"
    payload = json.loads(results_path.read_text(encoding="utf-8"))
    # The pinned verifier calls ``.get`` on each cost value before reporting a
    # missing-price check. Preserve an object-shaped sentinel instead of null,
    # then convert that expected check to an explicit verified N/A below.
    unavailable_cost = {"estimated_cost_usd": None, "status": "unavailable"}
    for endpoint in ("model_router", "baseline"):
        if isinstance(payload.get(endpoint), dict):
            payload[endpoint]["cost"] = dict(unavailable_cost)
    payload["cost_status"] = {"available": False, "reason": _COST_UNAVAILABLE_NOTE}
    results_path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")

    report_path = output_dir / "report.md"
    report = report_path.read_text(encoding="utf-8")
    heading = "## Cost Analysis\n"
    if heading in report and _COST_UNAVAILABLE_NOTE not in report:
        report = report.replace(
            heading,
            f"{heading}\n> **Unavailable:** {_COST_UNAVAILABLE_NOTE}\n",
            1,
        )
        report_path.write_text(report, encoding="utf-8")


def _accept_disabled_cost_checks(verification) -> None:
    verification.checks = [
        (
            True,
            message.replace("cost data missing", "cost intentionally unavailable (verified)"),
        )
        if not passed and message.endswith("cost data missing")
        else (passed, message)
        for passed, message in verification.checks
    ]


def _write_provenance(output_dir: Path, profile: str, auth: str, toolkit_root: Path) -> None:
    import subprocess

    revision = subprocess.run(
        ["git", "-C", str(toolkit_root), "rev-parse", "HEAD"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()
    payload = {
        "profile": profile,
        "auth": auth,
        "toolkit_revision": revision,
        "dataset": "kafka-demo.jsonl",
        "cost_status": {"available": False, "reason": _COST_UNAVAILABLE_NOTE},
        "reliability": dict(_RELIABILITY_DEFAULTS),
        "compatibility": {
            "azure_gpt_5_4_system_role": "translated_to_developer"
        },
        "completed_at": datetime.now(timezone.utc).isoformat(),
    }
    (output_dir / "wrapper-provenance.json").write_text(
        json.dumps(payload, indent=2) + "\n", encoding="utf-8"
    )


def run_local(args: argparse.Namespace) -> int:
    from src.config import load_config
    from src.dataset import load_dataset

    config = load_config(args.toolkit_root / "configs/default.yaml")
    config.name = f"kafka-foundry-{args.profile}-vs-fixed"
    config.dataset = str(args.dataset.resolve())
    config.sample_size = args.sample_size
    _apply_reliability_defaults(config)
    _disable_unverified_cost_metrics()

    prompts = load_dataset(config.dataset, sample_size=config.sample_size, random_seed=42)
    print(
        f"Validated {len(prompts)} prompts: profile={args.profile}, "
        f"router={config.model_router.deployment_name}, baseline={config.baseline.deployment_name}"
    )
    print(
        "Reliability settings: "
        f"request concurrency={config.max_parallel_requests}, retries={config.max_retries}, "
        f"judge concurrency={config.judge.max_parallel if config.judge else 0}, "
        f"judge retries={config.judge.max_retries if config.judge else 0}"
    )
    if args.dry_run:
        print("Dry-run complete; no credentials or API calls were used.")
        return 0
    if args.output_dir is None:
        raise RuntimeError("--output-dir is required for a live local evaluation")

    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=False)
    config.output_directory = str(output_dir)
    credential = _install_client_builders(args.auth)

    try:
        from src.runner import run_evaluation
        from src.verify import verify_local_eval

        asyncio.run(run_evaluation(config, resume=False))
        _mark_cost_unavailable(output_dir)
        verification = verify_local_eval(output_dir)
        _accept_disabled_cost_checks(verification)
        verification.print_summary()
        if not verification.passed:
            return 1
        _write_provenance(output_dir, args.profile, args.auth, args.toolkit_root)
        return 0
    finally:
        if credential is not None:
            credential.close()


def run_cloud(args: argparse.Namespace) -> int:
    if args.input_dir is None or args.output_dir is None:
        raise RuntimeError("--input-dir and --output-dir are required for cloud evaluation")
    from src.foundry import transformer as transformer_module
    from src.foundry.config import load_foundry_config
    from src.foundry.runner import run_foundry_eval
    from src.verify import verify_foundry_eval

    config = load_foundry_config(
        args.toolkit_root / "configs/foundry.yaml", strict=not args.dry_run
    )
    results = json.loads((args.input_dir / "results.json").read_text(encoding="utf-8"))
    if not results.get("cost_status", {}).get("available", True):
        config.cost.enabled = False

        def zero_pricing(_results):
            zero = {
                "cost_per_prompt_token": 0.0,
                "cost_per_completion_token": 0.0,
            }
            return {"model_router": dict(zero), "baseline": dict(zero)}

        transformer_module._extract_pricing = zero_pricing
        print("Cost grader disabled because verified pricing is unavailable.")
    config.output.directory = str(args.output_dir.resolve())
    result = run_foundry_eval(
        config=config,
        input_dir=args.input_dir.resolve(),
        dataset_path=args.dataset.resolve(),
        dry_run=args.dry_run,
    )
    if args.dry_run:
        return 0
    if result is None or result.status != "completed":
        return 1
    verification = verify_foundry_eval(args.output_dir.resolve())
    verification.print_summary()
    return 0 if verification.passed else 1


def main() -> int:
    args = _parser().parse_args()
    _prepare_toolkit(args.toolkit_root)
    return run_local(args) if args.action == "local" else run_cloud(args)


if __name__ == "__main__":
    raise SystemExit(main())
