#!/usr/bin/env python3
"""Safe, repo-local wrapper for Microsoft's Model Router Auto Evaluation toolkit."""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Mapping, Sequence
from urllib.parse import urlparse


ROOT = Path(__file__).resolve().parent
LOCK_FILE = ROOT / "toolkit.lock.json"
PROFILES_FILE = ROOT / "profiles.json"
CACHE_DIR = ROOT / ".cache"
TOOLKIT_DIR = CACHE_DIR / "toolkit"
VENV_DIR = ROOT / ".venv"
RESULTS_DIR = ROOT / "results"
ADAPTER = ROOT / "toolkit_adapter.py"
_SHA_RE = re.compile(r"^[0-9a-f]{40}$")
_VALID_DIFFICULTIES = {"easy", "medium", "hard"}


class EvalWrapperError(RuntimeError):
    """Raised for safe, user-actionable wrapper errors."""


def _read_json(path: Path) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise EvalWrapperError(f"Required file not found: {path}") from exc
    except json.JSONDecodeError as exc:
        raise EvalWrapperError(f"Invalid JSON in {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise EvalWrapperError(f"Expected a JSON object in {path}")
    return value


def load_lock() -> dict:
    lock = _read_json(LOCK_FILE)
    if lock.get("schema_version") != 1:
        raise EvalWrapperError("Unsupported toolkit lock schema")
    for field in ("repository", "revision", "tree", "commit_url"):
        if not isinstance(lock.get(field), str) or not lock[field]:
            raise EvalWrapperError(f"toolkit.lock.json is missing {field}")
    if not _SHA_RE.fullmatch(lock["revision"]) or not _SHA_RE.fullmatch(lock["tree"]):
        raise EvalWrapperError("Toolkit revision and tree must be full 40-character SHA-1 values")
    repository = urlparse(lock["repository"])
    if repository.scheme != "https" or repository.hostname != "github.com":
        raise EvalWrapperError("Toolkit repository must be an HTTPS github.com URL")
    if lock.get("runtime_constraints") != {"matplotlib": ">=3.7,<3.11"}:
        raise EvalWrapperError(
            "toolkit.lock.json must constrain Matplotlib to the verified >=3.7,<3.11 range"
        )
    return lock


def load_profiles() -> dict:
    profiles = _read_json(PROFILES_FILE)
    if profiles.get("schema_version") != 1:
        raise EvalWrapperError("Unsupported profile schema")
    configured = profiles.get("profiles")
    if not isinstance(configured, dict) or set(configured) != {"basic", "advanced"}:
        raise EvalWrapperError("profiles.json must define exactly basic and advanced profiles")
    for name, profile in configured.items():
        if not isinstance(profile, dict) or not profile.get("router_deployment_env"):
            raise EvalWrapperError(f"Profile {name} is missing router_deployment_env")
    return profiles


def dataset_path(profiles: dict | None = None) -> Path:
    profiles = profiles or load_profiles()
    relative = profiles.get("dataset")
    if not isinstance(relative, str) or not relative:
        raise EvalWrapperError("profiles.json is missing dataset")
    path = (ROOT / relative).resolve()
    if ROOT.resolve() not in path.parents:
        raise EvalWrapperError("Dataset must stay under tools/eval")
    return path


def validate_dataset(path: Path | None = None, minimum: int = 10) -> list[dict]:
    path = path or dataset_path()
    records: list[dict] = []
    seen_ids: set[str] = set()
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except FileNotFoundError as exc:
        raise EvalWrapperError(f"Dataset not found: {path}") from exc

    for line_number, raw_line in enumerate(lines, start=1):
        if not raw_line.strip():
            continue
        try:
            record = json.loads(raw_line)
        except json.JSONDecodeError as exc:
            raise EvalWrapperError(f"Dataset line {line_number} is invalid JSON: {exc}") from exc
        if not isinstance(record, dict):
            raise EvalWrapperError(f"Dataset line {line_number} must be an object")
        prompt_id = record.get("id")
        prompt = record.get("prompt")
        if not isinstance(prompt_id, str) or not prompt_id.strip():
            raise EvalWrapperError(f"Dataset line {line_number} has an invalid id")
        if prompt_id in seen_ids:
            raise EvalWrapperError(f"Dataset line {line_number} duplicates id {prompt_id!r}")
        if not isinstance(prompt, str) or not prompt.strip():
            raise EvalWrapperError(f"Dataset line {line_number} has an invalid prompt")
        if record.get("difficulty") not in _VALID_DIFFICULTIES:
            raise EvalWrapperError(
                f"Dataset line {line_number} difficulty must be easy, medium, or hard"
            )
        if not isinstance(record.get("category"), str) or not record["category"].strip():
            raise EvalWrapperError(f"Dataset line {line_number} has an invalid category")
        if not isinstance(record.get("ground_truth"), str) or not record["ground_truth"].strip():
            raise EvalWrapperError(f"Dataset line {line_number} has an invalid ground_truth")
        if not isinstance(record.get("metadata"), dict):
            raise EvalWrapperError(f"Dataset line {line_number} metadata must be an object")
        seen_ids.add(prompt_id)
        records.append(record)

    if len(records) < minimum:
        raise EvalWrapperError(f"Dataset needs at least {minimum} prompts; found {len(records)}")
    return records


def _run(
    command: Sequence[str],
    *,
    cwd: Path | None = None,
    env: Mapping[str, str] | None = None,
    capture: bool = False,
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        list(command),
        cwd=str(cwd) if cwd else None,
        env=dict(env) if env else None,
        check=True,
        text=True,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.PIPE if capture else None,
    )


def _git_output(checkout: Path, *args: str) -> str:
    try:
        return _run(["git", "-C", str(checkout), *args], capture=True).stdout.strip()
    except (FileNotFoundError, subprocess.CalledProcessError) as exc:
        raise EvalWrapperError(f"Unable to inspect toolkit checkout at {checkout}") from exc


def _normalize_git_url(value: str) -> str:
    return value.rstrip("/").removesuffix(".git").lower()


def verify_checkout(checkout: Path = TOOLKIT_DIR) -> dict:
    lock = load_lock()
    if not (checkout / ".git").is_dir():
        raise EvalWrapperError(f"Toolkit is not bootstrapped at {checkout}")
    revision = _git_output(checkout, "rev-parse", "HEAD")
    tree = _git_output(checkout, "rev-parse", "HEAD^{tree}")
    remote = _git_output(checkout, "remote", "get-url", "origin")
    status = _git_output(checkout, "status", "--porcelain", "--untracked-files=all")
    if revision != lock["revision"]:
        raise EvalWrapperError(f"Toolkit revision mismatch: expected {lock['revision']}, got {revision}")
    if tree != lock["tree"]:
        raise EvalWrapperError(f"Toolkit tree mismatch: expected {lock['tree']}, got {tree}")
    if _normalize_git_url(remote) != _normalize_git_url(lock["repository"]):
        raise EvalWrapperError("Toolkit origin does not match toolkit.lock.json")
    if status:
        raise EvalWrapperError("Toolkit checkout is dirty; remove it and bootstrap again")
    return {"revision": revision, "tree": tree, "repository": remote}


def verify_remote_pin() -> str:
    lock = load_lock()
    api_url = lock["commit_url"].replace("https://github.com/", "https://api.github.com/repos/")
    api_url = api_url.replace("/commit/", "/commits/")
    request = urllib.request.Request(
        api_url,
        headers={"Accept": "application/vnd.github+json", "User-Agent": "kafka-foundry-eval"},
    )
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            payload = json.load(response)
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
        raise EvalWrapperError(f"Could not verify remote commit: {exc}") from exc
    if not isinstance(payload, dict) or payload.get("sha") != lock["revision"]:
        raise EvalWrapperError("GitHub did not return the pinned toolkit revision")
    remote_tree = payload.get("commit", {}).get("tree", {}).get("sha")
    if remote_tree != lock["tree"]:
        raise EvalWrapperError(
            f"GitHub tree mismatch: expected {lock['tree']}, got {remote_tree}"
        )
    return payload["sha"]


def _venv_python() -> Path:
    candidate = VENV_DIR / ("Scripts/python.exe" if os.name == "nt" else "bin/python")
    if not candidate.is_file():
        raise EvalWrapperError("Evaluation virtual environment is missing; run bootstrap first")
    return candidate


def _bootstrap_python() -> str:
    requested = os.environ.get("EVAL_PYTHON", "").strip()
    candidates = [requested] if requested else [
        "python3.13",
        "python3.12",
        "python3.11",
        "python3.10",
        "python3.9",
        sys.executable,
    ]
    for value in candidates:
        executable = shutil.which(value) if not Path(value).is_absolute() else value
        if not executable:
            continue
        probe = subprocess.run(
            [
                executable,
                "-c",
                (
                    "import sys; "
                    "raise SystemExit(0 if (3, 9) <= sys.version_info[:2] <= (3, 13) else 1)"
                ),
            ],
            check=False,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        if probe.returncode == 0:
            return executable
    raise EvalWrapperError(
        "Bootstrap needs Python 3.9-3.13; set EVAL_PYTHON to a supported interpreter"
    )


def _venv_is_ready() -> bool:
    try:
        python = _venv_python()
    except EvalWrapperError:
        return False
    probe = subprocess.run(
        [str(python), "-c", "import pip, sys; raise SystemExit(sys.version_info[:2] > (3, 13))"],
        check=False,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    return probe.returncode == 0


def _create_venv() -> None:
    uv = shutil.which("uv")
    requested = os.environ.get("EVAL_PYTHON", "").strip()
    if uv:
        command = [
            uv,
            "venv",
            "--no-project",
            "--clear",
            "--seed",
            "--python",
            requested or "3.12",
        ]
        if not requested:
            command.append("--managed-python")
        command.append(str(VENV_DIR))
        _run(command)
        return
    _run([_bootstrap_python(), "-m", "venv", str(VENV_DIR)])


def verify_runtime_constraints() -> dict:
    script = """
import json
import matplotlib
from matplotlib import cm
from packaging.version import Version

version = Version(matplotlib.__version__)
has_get_cmap = hasattr(cm, "get_cmap")
if has_get_cmap:
    cm.get_cmap("tab10")
valid = Version("3.7") <= version < Version("3.11") and has_get_cmap
print(json.dumps({"matplotlib": str(version), "cm_get_cmap": has_get_cmap}))
raise SystemExit(0 if valid else 1)
"""
    try:
        completed = _run([str(_venv_python()), "-c", script], capture=True)
        result = json.loads(completed.stdout)
    except (subprocess.CalledProcessError, json.JSONDecodeError) as exc:
        raise EvalWrapperError(
            "Runtime constraint verification failed: Matplotlib must be >=3.7,<3.11 "
            "and provide matplotlib.cm.get_cmap"
        ) from exc
    return result


def bootstrap() -> None:
    lock = load_lock()
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    if TOOLKIT_DIR.exists():
        verify_checkout()
    else:
        temporary = Path(tempfile.mkdtemp(prefix="toolkit-", dir=CACHE_DIR))
        try:
            _run(
                [
                    "git",
                    "clone",
                    "--filter=blob:none",
                    "--no-checkout",
                    lock["repository"],
                    str(temporary),
                ]
            )
            _run(["git", "-C", str(temporary), "checkout", "--detach", lock["revision"]])
            verify_checkout(temporary)
            temporary.replace(TOOLKIT_DIR)
        except Exception:
            shutil.rmtree(temporary, ignore_errors=True)
            raise

    if not _venv_is_ready():
        if VENV_DIR.exists() and not shutil.which("uv"):
            shutil.rmtree(VENV_DIR)
        _create_venv()
    python = _venv_python()
    _run(
        [
            str(python),
            "-m",
            "pip",
            "install",
            "--disable-pip-version-check",
            "--no-cache-dir",
            (
                "foundry-model-router-eval[foundry] @ "
                f"git+{lock['repository']}@{lock['revision']}"
            ),
            f"matplotlib{lock['runtime_constraints']['matplotlib']}",
        ]
    )
    verify_checkout()
    _run(
        [
            str(python),
            "-c",
            "import azure.identity, openai, yaml; print('Toolkit dependencies import successfully')",
        ]
    )
    runtime = verify_runtime_constraints()
    print(
        "Runtime constraints valid: "
        f"matplotlib={runtime['matplotlib']}, cm.get_cmap={runtime['cm_get_cmap']}"
    )


def _required(environment: Mapping[str, str], name: str) -> str:
    value = environment.get(name, "").strip()
    if not value:
        raise EvalWrapperError(f"Required environment variable is not set: {name}")
    return value


def _validate_https_endpoint(name: str, value: str) -> str:
    parsed = urlparse(value)
    if parsed.scheme != "https" or not parsed.hostname:
        raise EvalWrapperError(f"{name} must be an HTTPS endpoint")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise EvalWrapperError(f"{name} must not contain credentials, query parameters, or fragments")
    return value.rstrip("/")


def build_toolkit_environment(
    profile_name: str,
    auth: str,
    source: Mapping[str, str] | None = None,
    *,
    placeholders: bool = False,
) -> dict[str, str]:
    profiles = load_profiles()
    if profile_name not in profiles["profiles"]:
        raise EvalWrapperError(f"Unknown profile: {profile_name}")
    environment = dict(source if source is not None else os.environ)
    profile = profiles["profiles"][profile_name]

    if placeholders:
        environment.setdefault("AZURE_MODEL_ROUTER_ENDPOINT", "https://placeholder.services.ai.azure.com/models")
        environment.setdefault("AZURE_OPENAI_ENDPOINT", "https://placeholder.openai.azure.com")
        environment.setdefault("AZURE_JUDGE_ENDPOINT", environment["AZURE_OPENAI_ENDPOINT"])
        environment.setdefault(profile["router_deployment_env"], f"model-router-{profile_name}")
        environment.setdefault(profiles["fixed_baseline_env"], "fixed-baseline")
        environment.setdefault(profiles["judge_deployment_env"], "fixed-judge")

    router_endpoint = _validate_https_endpoint(
        "AZURE_MODEL_ROUTER_ENDPOINT",
        _required(environment, "AZURE_MODEL_ROUTER_ENDPOINT"),
    )
    baseline_endpoint = _validate_https_endpoint(
        "AZURE_OPENAI_ENDPOINT",
        _required(environment, "AZURE_OPENAI_ENDPOINT"),
    )
    judge_endpoint = _validate_https_endpoint(
        "AZURE_JUDGE_ENDPOINT",
        environment.get("AZURE_JUDGE_ENDPOINT", baseline_endpoint),
    )
    router_deployment = _required(environment, profile["router_deployment_env"])
    baseline_deployment = _required(environment, profiles["fixed_baseline_env"])
    judge_deployment = _required(environment, profiles["judge_deployment_env"])

    environment["AZURE_MODEL_ROUTER_ENDPOINT"] = router_endpoint
    environment["AZURE_MODEL_ROUTER_DEPLOYMENT"] = router_deployment
    environment["AZURE_OPENAI_ENDPOINT"] = baseline_endpoint
    environment["AZURE_BASELINE_DEPLOYMENT"] = baseline_deployment
    environment["AZURE_JUDGE_ENDPOINT"] = judge_endpoint
    environment["AZURE_JUDGE_DEPLOYMENT"] = judge_deployment
    environment.pop("PYTHONPATH", None)

    if auth == "entra":
        sentinel = "unused-keyless-auth"
        environment["AZURE_MODEL_ROUTER_KEY"] = sentinel
        environment["AZURE_OPENAI_KEY"] = sentinel
        environment["AZURE_JUDGE_KEY"] = sentinel
    elif auth == "key":
        environment["AZURE_MODEL_ROUTER_KEY"] = _required(environment, "AZURE_MODEL_ROUTER_KEY")
        environment["AZURE_OPENAI_KEY"] = _required(environment, "AZURE_OPENAI_KEY")
        environment["AZURE_JUDGE_KEY"] = (
            environment.get("AZURE_JUDGE_KEY", "").strip()
            or environment["AZURE_OPENAI_KEY"]
        )
    else:
        raise EvalWrapperError("auth must be entra or key")
    return environment


def _safe_result_path(path: Path) -> Path:
    resolved = path.resolve()
    root = RESULTS_DIR.resolve()
    if resolved != root and root not in resolved.parents:
        raise EvalWrapperError("Result paths must stay under tools/eval/results")
    return resolved


def _new_run_path(profile_name: str) -> Path:
    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    return RESULTS_DIR / profile_name / f"run-{timestamp}-{uuid.uuid4().hex[:8]}"


def _adapter_command(
    action: str,
    *,
    profile_name: str | None = None,
    auth: str = "entra",
    output_dir: Path | None = None,
    input_dir: Path | None = None,
    sample_size: int | None = None,
    dry_run: bool = False,
) -> list[str]:
    command = [
        str(_venv_python()),
        str(ADAPTER),
        action,
        "--toolkit-root",
        str(TOOLKIT_DIR),
        "--dataset",
        str(dataset_path()),
    ]
    if profile_name:
        command.extend(["--profile", profile_name, "--auth", auth])
    if output_dir:
        command.extend(["--output-dir", str(_safe_result_path(output_dir))])
    if input_dir:
        command.extend(["--input-dir", str(_safe_result_path(input_dir))])
    if sample_size is not None:
        if sample_size <= 0:
            raise EvalWrapperError("sample-size must be positive")
        command.extend(["--sample-size", str(sample_size)])
    if dry_run:
        command.append("--dry-run")
    return command


def run_profile(
    profile_name: str,
    auth: str,
    sample_size: int | None,
    output_dir: Path | None = None,
) -> Path:
    verify_checkout()
    output_dir = _safe_result_path(output_dir or _new_run_path(profile_name))
    if output_dir.exists():
        raise EvalWrapperError(f"Refusing to overwrite existing output directory: {output_dir}")
    environment = build_toolkit_environment(profile_name, auth)
    _run(
        _adapter_command(
            "local",
            profile_name=profile_name,
            auth=auth,
            output_dir=output_dir,
            sample_size=sample_size,
        ),
        cwd=TOOLKIT_DIR,
        env=environment,
    )
    return output_dir


def run_matrix(auth: str, sample_size: int | None) -> Path:
    profiles = load_profiles()
    basic_env = profiles["profiles"]["basic"]["router_deployment_env"]
    advanced_env = profiles["profiles"]["advanced"]["router_deployment_env"]
    basic_deployment = _required(os.environ, basic_env)
    advanced_deployment = _required(os.environ, advanced_env)
    if basic_deployment == advanced_deployment:
        raise EvalWrapperError("Matrix runs require distinct basic and advanced router deployments")

    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    matrix_dir = _safe_result_path(
        RESULTS_DIR / f"matrix-{timestamp}-{uuid.uuid4().hex[:8]}"
    )
    basic_dir = run_profile("basic", auth, sample_size, matrix_dir / "basic")
    advanced_dir = run_profile("advanced", auth, sample_size, matrix_dir / "advanced")
    comparison = _run(
        [
            str(_venv_python()),
            str(TOOLKIT_DIR / "scripts/compare_results.py"),
            str(basic_dir),
            str(advanced_dir),
        ],
        cwd=TOOLKIT_DIR,
        capture=True,
    ).stdout
    (matrix_dir / "comparison.md").write_text(comparison, encoding="utf-8")
    return matrix_dir


def validate(*, official: bool, remote_pin: bool) -> None:
    lock = load_lock()
    profiles = load_profiles()
    records = validate_dataset(dataset_path(profiles))
    print(f"Lock valid: {lock['revision']} (tree {lock['tree']})")
    print(f"Profiles valid: {', '.join(sorted(profiles['profiles']))}")
    print(f"Dataset valid: {len(records)} Kafka prompts")
    if remote_pin:
        print(f"Remote commit valid: {verify_remote_pin()}")
    if TOOLKIT_DIR.exists():
        checkout = verify_checkout()
        print(f"Local checkout valid: {checkout['revision']}")
        if _venv_is_ready():
            runtime = verify_runtime_constraints()
            print(
                "Runtime constraints valid: "
                f"matplotlib={runtime['matplotlib']}, cm.get_cmap={runtime['cm_get_cmap']}"
            )
    else:
        print("Toolkit checkout: not bootstrapped (local validation still complete)")
    if official:
        verify_checkout()
        for profile_name in ("basic", "advanced"):
            environment = build_toolkit_environment(
                profile_name, "entra", placeholders=True
            )
            _run(
                _adapter_command(
                    "local",
                    profile_name=profile_name,
                    auth="entra",
                    dry_run=True,
                ),
                cwd=TOOLKIT_DIR,
                env=environment,
            )
        print("Official toolkit dry-run valid for basic and advanced profiles")


def run_cloud_eval(input_dir: Path, dry_run: bool) -> Path:
    verify_checkout()
    input_dir = _safe_result_path(input_dir)
    if not (input_dir / "raw_results.jsonl").is_file() or not (input_dir / "results.json").is_file():
        raise EvalWrapperError("Cloud evaluation input needs raw_results.jsonl and results.json")
    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    mode = "dry-run" if dry_run else "live"
    output_dir = _safe_result_path(
        input_dir / f"foundry-{mode}-{timestamp}-{uuid.uuid4().hex[:8]}"
    )
    environment = dict(os.environ)
    if dry_run:
        environment.setdefault(
            "AZURE_AI_PROJECT_ENDPOINT",
            "https://placeholder.services.ai.azure.com/api/projects/placeholder",
        )
        environment.setdefault("AZURE_AI_MODEL_DEPLOYMENT_NAME", "fixed-judge")
    else:
        _validate_https_endpoint(
            "AZURE_AI_PROJECT_ENDPOINT",
            _required(environment, "AZURE_AI_PROJECT_ENDPOINT"),
        )
        _required(environment, "AZURE_AI_MODEL_DEPLOYMENT_NAME")
    environment.pop("PYTHONPATH", None)
    _run(
        _adapter_command(
            "cloud",
            output_dir=output_dir,
            input_dir=input_dir,
            dry_run=dry_run,
        ),
        cwd=TOOLKIT_DIR,
        env=environment,
    )
    return output_dir


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Pinned Microsoft Model Router Auto Evaluation wrapper for the Kafka demo."
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    subparsers.add_parser("bootstrap", help="Clone the pinned toolkit and install it in an isolated venv")

    verify_parser = subparsers.add_parser("verify-pin", help="Verify the pinned local toolkit checkout")
    verify_parser.add_argument("--remote", action="store_true", help="Also verify the commit with GitHub")

    validate_parser = subparsers.add_parser("validate", help="Validate lock, profiles, and JSONL without API calls")
    validate_parser.add_argument("--official", action="store_true", help="Also run the pinned toolkit dry-run")
    validate_parser.add_argument("--remote-pin", action="store_true", help="Verify the commit with GitHub")

    run_parser = subparsers.add_parser("run", help="Run one router profile against the fixed baseline")
    run_parser.add_argument("--profile", choices=("basic", "advanced"), required=True)
    run_parser.add_argument("--auth", choices=("entra", "key"), default="entra")
    run_parser.add_argument("--sample-size", type=int)

    matrix_parser = subparsers.add_parser("matrix", help="Run basic and advanced profiles, then compare them")
    matrix_parser.add_argument("--auth", choices=("entra", "key"), default="entra")
    matrix_parser.add_argument("--sample-size", type=int)

    cloud_parser = subparsers.add_parser("cloud-eval", help="Submit a completed local run to Foundry Evaluation")
    cloud_parser.add_argument("--input-dir", type=Path, required=True)
    cloud_parser.add_argument("--dry-run", action="store_true")
    return parser


def main() -> int:
    args = build_parser().parse_args()
    try:
        if args.command == "bootstrap":
            bootstrap()
            print(f"Pinned toolkit ready: {load_lock()['revision']}")
        elif args.command == "verify-pin":
            checkout = verify_checkout()
            print(f"Local pin verified: {checkout['revision']} (tree {checkout['tree']})")
            if args.remote:
                print(f"Remote pin verified: {verify_remote_pin()}")
        elif args.command == "validate":
            validate(official=args.official, remote_pin=args.remote_pin)
        elif args.command == "run":
            output = run_profile(args.profile, args.auth, args.sample_size)
            print(f"Evaluation complete: {output}")
        elif args.command == "matrix":
            output = run_matrix(args.auth, args.sample_size)
            print(f"Matrix complete: {output}")
        elif args.command == "cloud-eval":
            output = run_cloud_eval(args.input_dir, args.dry_run)
            print(f"Foundry evaluation output: {output}")
        return 0
    except (EvalWrapperError, FileNotFoundError, subprocess.CalledProcessError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
