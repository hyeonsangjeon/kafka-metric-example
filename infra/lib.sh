#!/usr/bin/env bash

set -Eeuo pipefail
IFS=$'\n\t'

readonly INFRA_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly TEMPLATE_FILE="${INFRA_DIR}/main.json"

default_expiry_date() {
  local expires_on

  if expires_on="$(date -u -v+7d +%F 2>/dev/null)"; then
    printf '%s' "$expires_on"
  elif expires_on="$(date -u -d '+7 days' +%F 2>/dev/null)"; then
    printf '%s' "$expires_on"
  else
    printf '%s\n' "Unable to calculate the default seven-day expiry date. Set AZURE_EXPIRES_ON explicitly." >&2
    return 1
  fi
}

readonly LOCATION="${AZURE_LOCATION:-eastus2}"
readonly UNIQUE_SUFFIX="${AZURE_UNIQUE_SUFFIX:-replace-me}"
readonly RESOURCE_GROUP="${AZURE_RESOURCE_GROUP:-rg-kafka-router-${UNIQUE_SUFFIX}}"
readonly ACCOUNT_NAME="${AZURE_FOUNDRY_ACCOUNT:-aif-kafka-router-${UNIQUE_SUFFIX}}"
readonly PROJECT_NAME="${AZURE_FOUNDRY_PROJECT:-kafka-router-demo}"
readonly WORKSPACE_NAME="${AZURE_LOG_WORKSPACE:-law-kafka-router-${UNIQUE_SUFFIX}}"
readonly APP_INSIGHTS_NAME="${AZURE_APP_INSIGHTS:-appi-kafka-router-${UNIQUE_SUFFIX}}"
readonly APP_INSIGHTS_CONNECTION_NAME="${AZURE_APP_INSIGHTS_CONNECTION:-appinsights-demo}"

readonly FIXED_DEPLOYMENT="${FOUNDRY_FIXED_DEPLOYMENT:-gpt-5.4-mini}"
readonly FIXED_MODEL="gpt-5.4-mini"
readonly FIXED_MODEL_VERSION="2026-03-17"
readonly ROUTER_DEFAULT_DEPLOYMENT="${FOUNDRY_DEFAULT_ROUTER_DEPLOYMENT:-model-router-default}"
readonly ROUTER_COST_DEPLOYMENT="${FOUNDRY_COST_ROUTER_DEPLOYMENT:-model-router-cost}"
readonly ROUTER_MODEL="model-router"
readonly ROUTER_MODEL_VERSION="2025-11-18"
readonly MODEL_SKU="GlobalStandard"
readonly MODEL_CAPACITY="10"

readonly MANAGED_BY_TAG="kafka-metric-example-infra"
readonly LEGACY_MANAGED_BY_TAG="codex"
readonly PURPOSE_TAG="model-router-demo"
readonly REPOSITORY_TAG="${AZURE_REPOSITORY_TAG:-kafka-metric-example}"
readonly EXPIRES_ON_TAG="${AZURE_EXPIRES_ON:-$(default_expiry_date)}"
readonly DEPLOYMENT_NAME="${AZURE_DEPLOYMENT_NAME:-kafka-router-demo-infra}"
readonly ACCOUNT_API_VERSION="2026-03-01"
readonly CONNECTION_API_VERSION="2025-12-01"

info() {
  printf '[infra] %s\n' "$*"
}

die() {
  printf '[infra] ERROR: %s\n' "$*" >&2
  exit 1
}

require_unique_suffix() {
  [[ -n "${AZURE_UNIQUE_SUFFIX:-}" ]] \
    || die "Set AZURE_UNIQUE_SUFFIX to a stable, globally unique lowercase suffix before using the infrastructure scripts."
  [[ "$UNIQUE_SUFFIX" =~ ^[a-z0-9][a-z0-9-]{1,18}[a-z0-9]$ ]] \
    || die "AZURE_UNIQUE_SUFFIX must be 3-20 lowercase letters, digits, or hyphens and cannot start or end with a hyphen."
  [[ "$EXPIRES_ON_TAG" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] \
    || die "AZURE_EXPIRES_ON must use YYYY-MM-DD."
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "Required command not found: $1"
}

require_tools() {
  require_command az
  require_command jq
}

require_azure_login() {
  az account show --only-show-errors --output none >/dev/null 2>&1 \
    || die "Azure CLI is not logged in. Run 'az login' and select the intended subscription."
}

validate_object_id() {
  [[ "$1" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]] \
    || die "The principal object ID must be a UUID."
}

resolve_principal_object_id() {
  local supplied="${1:-}"
  local principal_type
  local resolved

  if [[ -n "$supplied" ]]; then
    validate_object_id "$supplied"
    printf '%s' "$supplied"
    return
  fi

  principal_type="$(az account show --query user.type --output tsv --only-show-errors)"
  [[ "$principal_type" == "user" ]] \
    || die "Non-user Azure identities must pass --principal-object-id explicitly."

  resolved="$(az ad signed-in-user show --query id --output tsv --only-show-errors)"
  validate_object_id "$resolved"
  printf '%s' "$resolved"
}

resource_group_exists() {
  [[ "$(az group exists --name "$RESOURCE_GROUP" --output tsv --only-show-errors)" == "true" ]]
}

account_exists() {
  az cognitiveservices account show \
    --name "$ACCOUNT_NAME" \
    --resource-group "$RESOURCE_GROUP" \
    --output none \
    --only-show-errors >/dev/null 2>&1
}

print_plan() {
  cat <<EOF
Azure write operations are disabled in this dry run.

Location:             ${LOCATION}
Resource group:       ${RESOURCE_GROUP}
Unique suffix:        ${UNIQUE_SUFFIX}
Foundry account:      ${ACCOUNT_NAME} (AIServices, local auth disabled)
Foundry project:      ${PROJECT_NAME}
Fixed deployment:     ${FIXED_DEPLOYMENT} -> ${FIXED_MODEL} ${FIXED_MODEL_VERSION}
Default router:       ${ROUTER_DEFAULT_DEPLOYMENT} -> ${ROUTER_MODEL} ${ROUTER_MODEL_VERSION}, balanced/all supported models
Advanced router:      ${ROUTER_COST_DEPLOYMENT} -> cost subset [gpt-5.4-nano, gpt-5.4-mini, gpt-5.4]
Deployment SKU:       ${MODEL_SKU}, capacity ${MODEL_CAPACITY} each
Log Analytics:        ${WORKSPACE_NAME}, 0.1 GB/day cap, 30-day retention
Application Insights: ${APP_INSIGHTS_NAME}, workspace based
RBAC:                 Foundry User + Monitoring Reader/Publisher
Cleanup boundary:     the dedicated ${RESOURCE_GROUP} resource group
Advisory expiry tag:  ${EXPIRES_ON_TAG}

No key, access token, connection string, subscription ID, tenant ID, or principal ID is printed or written to disk.
EOF
}

print_application_configuration() {
  cat <<EOF

Application configuration (contains no credential):
  FOUNDRY_PROJECT_ENDPOINT=https://${ACCOUNT_NAME}.services.ai.azure.com/api/projects/${PROJECT_NAME}
  FOUNDRY_MODEL=${FIXED_DEPLOYMENT}
  FOUNDRY_ROUTER_DEFAULT_MODEL=${ROUTER_DEFAULT_DEPLOYMENT}
  FOUNDRY_ROUTER_ADVANCED_MODEL=${ROUTER_COST_DEPLOYMENT}
  FOUNDRY_ROUTER_ADVANCED_PROFILE=cost
EOF
}
