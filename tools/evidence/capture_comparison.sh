#!/usr/bin/env bash

set -Eeuo pipefail
IFS=$'\n\t'

usage() {
  cat <<'EOF'
Usage:
  ./tools/evidence/capture_comparison.sh OUTPUT_DIRECTORY

Optional environment:
  BASE_URL=http://127.0.0.1:8080
  EXPECTED_PROVIDER_MODE=foundry
  SOURCE_VERSION=v1.3.0
  SOURCE_COMMIT=0123456789abcdef0123456789abcdef01234567
  SOURCE_ARTIFACT=git:v1.3.0
  COMPARISON_WORKLOAD=chat
  COMPARISON_SCENARIO=healthy
  COMPARISON_TRAFFIC=1
  COMPARISON_PROMPT='Explain one practical Kafka reliability signal.'

Only use public or synthetic prompt text. The tool retains no provider response
body, raw snapshot, trace ID, hash, endpoint, deployment, or cloud locator.
EOF
}

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

[[ $# -eq 1 ]] || {
  usage >&2
  exit 2
}

for tool in curl jq mktemp mv; do
  command -v "$tool" >/dev/null 2>&1 || die "${tool} is required"
done

output_dir="$1"
base_url="${BASE_URL:-http://127.0.0.1:8080}"
expected_provider_mode="${EXPECTED_PROVIDER_MODE:-foundry}"
workload="${COMPARISON_WORKLOAD:-chat}"
scenario="${COMPARISON_SCENARIO:-healthy}"
traffic="${COMPARISON_TRAFFIC:-1}"
prompt="${COMPARISON_PROMPT:-Explain one practical Kafka reliability signal.}"
source_version="${SOURCE_VERSION:-}"
source_commit="${SOURCE_COMMIT:-}"
source_artifact="${SOURCE_ARTIFACT:-}"

if [[ ! "$base_url" =~ ^http://(127\.0\.0\.1|localhost):([0-9]{1,5})/?$ ]]; then
  die "BASE_URL must be exactly http://127.0.0.1:PORT or http://localhost:PORT"
fi
port="${BASH_REMATCH[2]}"
((port >= 1 && port <= 65535)) || die "BASE_URL port must be between 1 and 65535"
base_url="${base_url%/}"
[[ "$expected_provider_mode" =~ ^(foundry|simulated)$ ]] \
  || die "EXPECTED_PROVIDER_MODE must be foundry or simulated"
if [[ "$expected_provider_mode" == "foundry" ]]; then
  [[ "$source_version" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] \
    || die "SOURCE_VERSION must be a release tag such as v1.3.0"
  [[ "$source_commit" =~ ^[0-9a-f]{40}$ ]] \
    || die "SOURCE_COMMIT must be a full lowercase Git commit SHA"
  [[ "$source_artifact" =~ ^(git:v[0-9]+\.[0-9]+\.[0-9]+|ghcr\.io/[a-z0-9._/-]+@sha256:[0-9a-f]{64})$ ]] \
    || die "SOURCE_ARTIFACT must be git:vX.Y.Z or an immutable GHCR sha256 digest"
fi

[[ "$traffic" =~ ^[1-9][0-9]*$ ]] || die "COMPARISON_TRAFFIC must be a positive integer"
[[ ! -e "$output_dir" ]] || die "output directory already exists: ${output_dir}"

tmp_dir="$(mktemp -d)"
cleanup() {
  rm -rf -- "$tmp_dir"
}
trap cleanup EXIT

curl_args=(--disable --fail --silent --show-error --noproxy '*' --connect-timeout 2 --max-time 15)
curl "${curl_args[@]}" "${base_url}/api/v1/health" >"${tmp_dir}/health.json"
curl "${curl_args[@]}" "${base_url}/api/v1/config" >"${tmp_dir}/config.json"

jq -e '.ready == true' "${tmp_dir}/health.json" >/dev/null \
  || die "application is not ready"
jq -e \
  --arg expected_mode "$expected_provider_mode" \
  '.mode == $expected_mode
    and .comparison.available == true
    and (.comparison.profiles | map(.id)) == ["fixed", "router-default", "router-advanced"]' \
  "${tmp_dir}/config.json" >/dev/null \
  || die "expected ${expected_provider_mode} with fixed/default/advanced comparison profiles"
max_traffic="$(jq -r '.comparison.maxTrafficPerProfile' "${tmp_dir}/config.json")"
[[ "$max_traffic" =~ ^[1-9][0-9]*$ ]] \
  || die "server returned an invalid maxTrafficPerProfile"
((traffic <= max_traffic)) \
  || die "traffic ${traffic} exceeds maxTrafficPerProfile ${max_traffic}"

jq -n \
  --arg workload "$workload" \
  --arg scenario "$scenario" \
  --argjson traffic "$traffic" \
  --arg prompt "$prompt" \
  '{workload:$workload,scenario:$scenario,traffic:$traffic,prompt:$prompt}' \
  >"${tmp_dir}/request.json"

curl "${curl_args[@]}" \
  -H 'Content-Type: application/json' \
  --data-binary "@${tmp_dir}/request.json" \
  "${base_url}/api/v1/comparisons" \
  >"${tmp_dir}/receipt.json"

comparison_id="$(jq -r '.comparisonId // empty' "${tmp_dir}/receipt.json")"
[[ -n "$comparison_id" ]] || die "comparison receipt did not include comparisonId"

terminal="false"
poll_deadline=$((SECONDS + 300))
while ((SECONDS < poll_deadline)); do
  remaining_seconds=$((poll_deadline - SECONDS))
  poll_timeout="$remaining_seconds"
  ((poll_timeout > 15)) && poll_timeout=15
  curl "${curl_args[@]}" --max-time "$poll_timeout" \
    "${base_url}/api/v1/comparisons/${comparison_id}" \
    >"${tmp_dir}/comparison.json"
  status="$(jq -r '.status // empty' "${tmp_dir}/comparison.json")"
  case "$status" in
    completed)
      terminal="true"
      break
      ;;
    failed|stopped)
      die "comparison ended with non-success status: ${status}"
      ;;
  esac
  ((SECONDS < poll_deadline)) && sleep 1
done
[[ "$terminal" == "true" ]] || die "comparison did not become terminal within five minutes"
jq -e '
    .status == "completed"
    and .profiles == ["fixed", "router-default", "router-advanced"]
    and (.providerInvocations <= .providerInvocationLimit)
    and (.phases | map(.profile)) == ["fixed", "router-default", "router-advanced"]
    and all(.phases[];
      .status == "completed"
      and .completed == .requested
      and .failed == 0
      and .successRate == 100
      and .tokenSamples == .completed
      and (.models | length) > 0)
  ' "${tmp_dir}/comparison.json" >/dev/null \
  || die "comparison completed without a fully successful, token-complete three-profile result"
staging_dir="${tmp_dir}/output"
mkdir "$staging_dir"

# The request is deliberately public/synthetic and is retained as the exact
# reproducible input. Session-local comparison/run handles are normalized in
# every public result artifact.
jq '.' "${tmp_dir}/request.json" >"${staging_dir}/comparison-input.json"
jq '{
      status,
      provider,
      transport,
      ready,
      apiVersion
    }' "${tmp_dir}/health.json" >"${staging_dir}/health-summary.json"
jq '{
      mode,
      transport,
      cloudReady,
      maxTrafficPerRun,
      modelProfiles: [.modelProfiles[] | {
        id, label, strategy, description, router
      }],
      comparison: {
        available: .comparison.available,
        unavailableReason: .comparison.unavailableReason,
        profiles: [.comparison.profiles[] | {
          id, label, kind, routeStrategy
        }],
        profileCount: .comparison.profileCount,
        trafficSemantics: .comparison.trafficSemantics,
        defaultTrafficPerProfile: .comparison.defaultTrafficPerProfile,
        maxTrafficPerProfile: .comparison.maxTrafficPerProfile,
        providerInvocationLimit: .comparison.providerInvocationLimit
      },
      defaults: {
        workload: .defaults.workload,
        traffic: .defaults.traffic,
        scenario: .defaults.scenario,
        modelId: .defaults.modelId,
        modelProfile: .defaults.modelProfile
      }
    }' "${tmp_dir}/config.json" >"${staging_dir}/configuration-summary.json"
jq '{
      comparisonId: "comparison-demo",
      status,
      profiles,
      trafficPerProfile,
      plannedRequests,
      providerInvocationLimit
    }' \
  "${tmp_dir}/receipt.json" >"${staging_dir}/comparison-receipt.json"
jq '{
      comparisonId: "comparison-demo",
      status,
      workload,
      scenario,
      trafficPerProfile,
      profiles,
      currentProfile,
      plannedRequests,
      providerInvocations,
      providerInvocationLimit,
      startedAt,
      completedAt,
      phases: [.phases[] | {
        profile,
        label,
        kind,
        routeStrategy,
        status,
        runId: (if .runId == null then null else ("phase-" + .profile) end),
        requested,
        completed,
        failed,
        retries,
        successRate,
        p50LatencyMs,
        p95LatencyMs,
        inputTokens,
        outputTokens,
        tokenSamples,
        models: [(.models // [])[] | {modelFamily, count, percentage}],
        p95DeltaMs,
        p95DeltaPercent,
        totalTokensDelta,
        totalTokensDeltaPercent
      }]
    }' "${tmp_dir}/comparison.json" >"${staging_dir}/comparison-result.json"

captured_at="$(date -u +'%Y-%m-%dT%H:%M:%SZ')"
jq -n \
  --arg capturedAtUtc "$captured_at" \
  --arg status "$(jq -r '.status' "${tmp_dir}/comparison.json")" \
  --arg source "loopback application API" \
  --arg sourceVersion "$source_version" \
  --arg sourceCommit "$source_commit" \
  --arg sourceArtifact "$source_artifact" \
  '{
    schemaVersion:1,
    capturedAtUtc:$capturedAtUtc,
    source:$source,
    sourceVersion:($sourceVersion | if length > 0 then . else null end),
    sourceCommit:($sourceCommit | if length > 0 then . else null end),
    sourceArtifact:($sourceArtifact | if length > 0 then . else null end),
    comparisonStatus:$status,
    identifiersNormalized:true,
    syntheticOrPublicPromptRequired:true,
    rawSnapshotRetained:false,
    providerResponseBodiesRetained:false
  }' >"${staging_dir}/capture-metadata.json"

for artifact in \
  health-summary.json \
  configuration-summary.json \
  comparison-receipt.json \
  comparison-result.json; do
  if jq -e '
      [paths as $path
        | ($path[-1] | tostring)
        | select(test("prompt|responseBody|responseText|responseContent|hash|trace|endpoint|deployment|subscription|tenant|principal|resourceId"; "i"))]
      | length > 0
    ' "${staging_dir}/${artifact}" >/dev/null; then
    die "${artifact} contains a forbidden public-evidence key"
  fi
  if jq -e '
      [.. | strings
        | select(test("https?://|/subscriptions/|/tenants/|/resourceGroups/"; "i"))]
      | length > 0
    ' "${staging_dir}/${artifact}" >/dev/null; then
    die "${artifact} contains a forbidden public-evidence locator"
  fi
done

mkdir -p "$(dirname "$output_dir")"
mv -- "$staging_dir" "$output_dir"

printf 'Captured privacy-safe comparison evidence in %s\n' "$output_dir"
