from __future__ import annotations

import asyncio
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("kafka_eval_wrapper", ROOT / "eval.py")
assert SPEC and SPEC.loader
WRAPPER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(WRAPPER)

ADAPTER_SPEC = importlib.util.spec_from_file_location(
    "kafka_eval_adapter", ROOT / "toolkit_adapter.py"
)
assert ADAPTER_SPEC and ADAPTER_SPEC.loader
ADAPTER = importlib.util.module_from_spec(ADAPTER_SPEC)
ADAPTER_SPEC.loader.exec_module(ADAPTER)


class EvalWrapperTests(unittest.TestCase):
    def test_toolkit_is_pinned_to_full_commit_and_tree(self):
        lock = WRAPPER.load_lock()
        self.assertEqual(lock["revision"], "ecf0aa26b4b613536a2ecb23ac1231b380c064f1")
        self.assertEqual(lock["tree"], "599225a9e437e016044aa75fd8d8d60d09205b49")
        self.assertTrue(lock["repository"].startswith("https://github.com/microsoft-foundry/"))
        self.assertEqual(lock["runtime_constraints"]["matplotlib"], ">=3.7,<3.11")

    def test_dataset_has_twelve_unique_well_formed_prompts(self):
        records = WRAPPER.validate_dataset()
        self.assertEqual(len(records), 12)
        self.assertEqual(len({record["id"] for record in records}), 12)
        self.assertTrue({"privacy", "evaluation_design", "incident_diagnosis"}.issubset(
            {record["category"] for record in records}
        ))

    def test_profiles_share_one_fixed_baseline_but_use_distinct_router_envs(self):
        profiles = WRAPPER.load_profiles()
        basic = profiles["profiles"]["basic"]["router_deployment_env"]
        advanced = profiles["profiles"]["advanced"]["router_deployment_env"]
        self.assertNotEqual(basic, advanced)
        self.assertEqual(profiles["fixed_baseline_env"], "AZURE_BASELINE_DEPLOYMENT")

    def test_keyless_environment_maps_profile_without_api_keys(self):
        environment = {
            "AZURE_MODEL_ROUTER_ENDPOINT": "https://router.services.ai.azure.com/models",
            "AZURE_OPENAI_ENDPOINT": "https://baseline.openai.azure.com",
            "AZURE_MODEL_ROUTER_BASIC_DEPLOYMENT": "router-default",
            "AZURE_BASELINE_DEPLOYMENT": "gpt-fixed",
            "AZURE_JUDGE_DEPLOYMENT": "gpt-judge",
        }
        result = WRAPPER.build_toolkit_environment("basic", "entra", environment)
        self.assertEqual(result["AZURE_MODEL_ROUTER_DEPLOYMENT"], "router-default")
        self.assertEqual(result["AZURE_BASELINE_DEPLOYMENT"], "gpt-fixed")
        self.assertEqual(result["AZURE_MODEL_ROUTER_KEY"], "unused-keyless-auth")
        self.assertNotIn("PYTHONPATH", result)

    def test_key_auth_rejects_missing_secrets(self):
        environment = {
            "AZURE_MODEL_ROUTER_ENDPOINT": "https://router.services.ai.azure.com/models",
            "AZURE_OPENAI_ENDPOINT": "https://baseline.openai.azure.com",
            "AZURE_MODEL_ROUTER_ADVANCED_DEPLOYMENT": "router-custom",
            "AZURE_BASELINE_DEPLOYMENT": "gpt-fixed",
            "AZURE_JUDGE_DEPLOYMENT": "gpt-judge",
        }
        with self.assertRaises(WRAPPER.EvalWrapperError):
            WRAPPER.build_toolkit_environment("advanced", "key", environment)

    def test_results_and_secrets_are_ignored_locally(self):
        patterns = (ROOT / ".gitignore").read_text(encoding="utf-8").splitlines()
        self.assertIn("results/", patterns)
        self.assertIn(".env", patterns)
        self.assertIn(".cache/", patterns)
        self.assertIn(".venv/", patterns)

    def test_jsonl_is_parseable_one_object_per_nonempty_line(self):
        path = ROOT / "datasets/kafka-demo.jsonl"
        for line in path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                self.assertIsInstance(json.loads(line), dict)

    def test_retired_models_suffix_is_removed_for_azure_openai_client(self):
        endpoint = "https://router.services.ai.azure.com/models/"
        self.assertEqual(
            ADAPTER._azure_openai_endpoint(endpoint),
            "https://router.services.ai.azure.com",
        )

    def test_cost_unavailable_note_explains_prefix_mispricing_risk(self):
        self.assertIn("prefix", ADAPTER._COST_UNAVAILABLE_NOTE)
        self.assertIn("unavailable", ADAPTER._COST_UNAVAILABLE_NOTE)

    def test_cost_unavailable_uses_verifier_safe_object_sentinel(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            (output / "results.json").write_text(
                json.dumps({"model_router": {"cost": None}, "baseline": {"cost": None}}),
                encoding="utf-8",
            )
            (output / "report.md").write_text("## Cost Analysis\n", encoding="utf-8")
            ADAPTER._mark_cost_unavailable(output)
            payload = json.loads((output / "results.json").read_text(encoding="utf-8"))
            self.assertIsNone(payload["model_router"]["cost"]["estimated_cost_usd"])
            self.assertEqual(payload["baseline"]["cost"]["status"], "unavailable")
            self.assertFalse(payload["cost_status"]["available"])

    def test_bootstrap_selects_a_supported_python(self):
        executable = WRAPPER._bootstrap_python()
        self.assertTrue(Path(executable).is_file())

    def test_reliability_defaults_serialize_requests_and_retry_transient_failures(self):
        judge = SimpleNamespace(max_parallel=9, max_retries=1)
        config = SimpleNamespace(
            max_parallel_requests=9,
            max_retries=1,
            judge=judge,
        )
        ADAPTER._apply_reliability_defaults(config)
        self.assertEqual(config.max_parallel_requests, 1)
        self.assertEqual(config.max_retries, 5)
        self.assertEqual(config.judge.max_parallel, 1)
        self.assertEqual(config.judge.max_retries, 5)

    def test_reliability_defaults_allow_disabled_judge(self):
        config = SimpleNamespace(max_parallel_requests=9, max_retries=1, judge=None)
        ADAPTER._apply_reliability_defaults(config)
        self.assertEqual(config.max_parallel_requests, 1)
        self.assertEqual(config.max_retries, 5)

    def test_gpt54_system_role_is_translated_to_developer_without_mutation(self):
        messages = [
            {"role": "system", "content": "grade carefully"},
            {"role": "user", "content": "compare these answers"},
        ]
        translated = ADAPTER._translate_azure_gpt54_messages(
            "gpt-5.4-mini", messages
        )
        self.assertEqual(translated[0]["role"], "developer")
        self.assertEqual(translated[1]["role"], "user")
        self.assertEqual(messages[0]["role"], "system")

    def test_non_gpt54_roles_are_not_translated(self):
        messages = [{"role": "system", "content": "grade carefully"}]
        translated = ADAPTER._translate_azure_gpt54_messages(
            "gpt-5-mini", messages
        )
        self.assertIs(translated, messages)
        self.assertEqual(translated[0]["role"], "system")

    def test_gpt54_client_wrapper_translates_messages_at_call_boundary(self):
        class FakeCompletions:
            def __init__(self):
                self.received = None

            async def create(self, **kwargs):
                self.received = kwargs["messages"]
                return "ok"

        completions = FakeCompletions()
        client = SimpleNamespace(
            chat=SimpleNamespace(completions=completions)
        )
        wrapped = ADAPTER._wrap_azure_gpt54_roles(client, "gpt-5.4-mini")
        result = asyncio.run(
            wrapped.chat.completions.create(
                messages=[
                    {"role": "system", "content": "judge"},
                    {"role": "user", "content": "compare"},
                ]
            )
        )
        self.assertEqual(result, "ok")
        self.assertEqual(completions.received[0]["role"], "developer")
        self.assertEqual(completions.received[1]["role"], "user")


if __name__ == "__main__":
    unittest.main()
