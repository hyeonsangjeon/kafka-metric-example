#!/usr/bin/env bash

set -Eeuo pipefail
IFS=$'\n\t'

# shellcheck source=lib.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

usage() {
  cat <<EOF
Usage:
  ./infra/verify.sh [--principal-object-id UUID]

Runs read-only control-plane checks. It does not call a model, consume model
tokens, retrieve a key, or print Azure resource IDs, secrets,
principal/subscription IDs, or role-assignment IDs. Its final safe application
configuration can include resource endpoint and deployment names.
Use the same required AZURE_UNIQUE_SUFFIX value used for provisioning.
EOF
}

principal_object_id=""
pass_count=0

while (($#)); do
  case "$1" in
    --principal-object-id)
      (($# >= 2)) || die "--principal-object-id requires a UUID."
      principal_object_id="$2"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      usage >&2
      die "Unknown argument: $1"
      ;;
  esac
done

assert_equal() {
  local label="$1"
  local expected="$2"
  local actual="$3"
  [[ "$actual" == "$expected" ]] || die "Verification failed: ${label}."
  pass_count=$((pass_count + 1))
  info "PASS ${label}"
}

assert_nonzero() {
  local label="$1"
  local actual="$2"
  [[ "$actual" =~ ^[0-9]+$ ]] && ((actual > 0)) \
    || die "Verification failed: ${label}."
  pass_count=$((pass_count + 1))
  info "PASS ${label}"
}

deployment_field() {
  local deployment_name="$1"
  local query="$2"
  az cognitiveservices account deployment show \
    --name "$ACCOUNT_NAME" \
    --resource-group "$RESOURCE_GROUP" \
    --deployment-name "$deployment_name" \
    --query "$query" \
    --output tsv \
    --only-show-errors
}

require_unique_suffix
require_tools
require_azure_login
principal_object_id="$(resolve_principal_object_id "$principal_object_id")"
subscription_id="$(az account show --query id --output tsv --only-show-errors)"

resource_group_exists || die "Resource group ${RESOURCE_GROUP} does not exist."
assert_equal "resource-group ownership tag" "$MANAGED_BY_TAG" "$(az group show --name "$RESOURCE_GROUP" --query 'tags."managed-by"' --output tsv --only-show-errors)"
assert_equal "resource-group location" "$LOCATION" "$(az group show --name "$RESOURCE_GROUP" --query location --output tsv --only-show-errors)"

account_json="$(az cognitiveservices account show \
  --name "$ACCOUNT_NAME" \
  --resource-group "$RESOURCE_GROUP" \
  --query '{kind:kind,location:location,sku:sku.name,state:properties.provisioningState,projects:properties.allowProjectManagement,localAuth:properties.disableLocalAuth,network:properties.publicNetworkAccess,domain:properties.customSubDomainName,managedBy:tags."managed-by"}' \
  --output json \
  --only-show-errors)"
assert_equal "Foundry account kind" "AIServices" "$(jq -r .kind <<<"$account_json")"
assert_equal "Foundry account location" "$LOCATION" "$(jq -r .location <<<"$account_json")"
assert_equal "Foundry account SKU" "S0" "$(jq -r .sku <<<"$account_json")"
assert_equal "Foundry account provisioning state" "Succeeded" "$(jq -r .state <<<"$account_json")"
assert_equal "Foundry project management" "true" "$(jq -r .projects <<<"$account_json")"
assert_equal "Foundry local authentication disabled" "true" "$(jq -r .localAuth <<<"$account_json")"
assert_equal "Foundry public endpoint enabled" "Enabled" "$(jq -r .network <<<"$account_json")"
assert_equal "Foundry custom subdomain" "$ACCOUNT_NAME" "$(jq -r .domain <<<"$account_json")"
assert_equal "Foundry ownership tag" "$MANAGED_BY_TAG" "$(jq -r .managedBy <<<"$account_json")"

project_json="$(az cognitiveservices account project show \
  --name "$ACCOUNT_NAME" \
  --resource-group "$RESOURCE_GROUP" \
  --project-name "$PROJECT_NAME" \
  --query '{location:location,state:properties.provisioningState,identity:identity.type,managedBy:tags."managed-by"}' \
  --output json \
  --only-show-errors)"
assert_equal "Foundry project location" "$LOCATION" "$(jq -r .location <<<"$project_json")"
assert_equal "Foundry project provisioning state" "Succeeded" "$(jq -r .state <<<"$project_json")"
assert_equal "Foundry project managed identity" "SystemAssigned" "$(jq -r .identity <<<"$project_json")"
assert_equal "Foundry project ownership tag" "$MANAGED_BY_TAG" "$(jq -r .managedBy <<<"$project_json")"

assert_equal "fixed deployment model" "$FIXED_MODEL" "$(deployment_field "$FIXED_DEPLOYMENT" properties.model.name)"
assert_equal "fixed deployment version" "$FIXED_MODEL_VERSION" "$(deployment_field "$FIXED_DEPLOYMENT" properties.model.version)"
assert_equal "fixed deployment SKU" "$MODEL_SKU" "$(deployment_field "$FIXED_DEPLOYMENT" sku.name)"
assert_equal "fixed deployment capacity" "$MODEL_CAPACITY" "$(deployment_field "$FIXED_DEPLOYMENT" sku.capacity)"

assert_equal "default router model" "$ROUTER_MODEL" "$(deployment_field "$ROUTER_DEFAULT_DEPLOYMENT" properties.model.name)"
assert_equal "default router version" "$ROUTER_MODEL_VERSION" "$(deployment_field "$ROUTER_DEFAULT_DEPLOYMENT" properties.model.version)"
default_router_mode="$(deployment_field "$ROUTER_DEFAULT_DEPLOYMENT" properties.routing.mode)"
[[ -z "$default_router_mode" || "$default_router_mode" == "balanced" ]] \
  || die "Verification failed: default router mode."
pass_count=$((pass_count + 1))
info "PASS default router uses implicit balanced mode"
default_router_models="$(az cognitiveservices account deployment show \
  --name "$ACCOUNT_NAME" \
  --resource-group "$RESOURCE_GROUP" \
  --deployment-name "$ROUTER_DEFAULT_DEPLOYMENT" \
  --query properties.routing.models \
  --output json \
  --only-show-errors)"
default_router_model_count="0"
if [[ -n "$default_router_models" ]]; then
  default_router_model_count="$(jq 'if . == null then 0 else length end' <<<"$default_router_models")"
fi
assert_equal "default router uses all supported models" "0" "$default_router_model_count"

assert_equal "advanced router model" "$ROUTER_MODEL" "$(deployment_field "$ROUTER_COST_DEPLOYMENT" properties.model.name)"
assert_equal "advanced router version" "$ROUTER_MODEL_VERSION" "$(deployment_field "$ROUTER_COST_DEPLOYMENT" properties.model.version)"
assert_equal "advanced router mode" "cost" "$(deployment_field "$ROUTER_COST_DEPLOYMENT" properties.routing.mode)"
cost_router_models="$(az cognitiveservices account deployment show \
  --name "$ACCOUNT_NAME" \
  --resource-group "$RESOURCE_GROUP" \
  --deployment-name "$ROUTER_COST_DEPLOYMENT" \
  --query properties.routing.models \
  --output json \
  --only-show-errors | jq -c 'map({format, name, version}) | sort_by(.name)')"
expected_cost_models='[{"format":"OpenAI","name":"gpt-5.4","version":"2026-03-05"},{"format":"OpenAI","name":"gpt-5.4-mini","version":"2026-03-17"},{"format":"OpenAI","name":"gpt-5.4-nano","version":"2026-03-17"}]'
assert_equal "advanced router model subset" "$expected_cost_models" "$cost_router_models"

workspace_json="$(az monitor log-analytics workspace show \
  --resource-group "$RESOURCE_GROUP" \
  --workspace-name "$WORKSPACE_NAME" \
  --query '{location:location,state:provisioningState,retention:retentionInDays,quota:workspaceCapping.dailyQuotaGb,managedBy:tags."managed-by"}' \
  --output json \
  --only-show-errors)"
assert_equal "Log Analytics location" "$LOCATION" "$(jq -r .location <<<"$workspace_json")"
assert_equal "Log Analytics provisioning state" "Succeeded" "$(jq -r .state <<<"$workspace_json")"
assert_equal "Log Analytics retention" "30" "$(jq -r .retention <<<"$workspace_json")"
assert_equal "Log Analytics daily cap" "0.1" "$(jq -r .quota <<<"$workspace_json")"
assert_equal "Log Analytics ownership tag" "$MANAGED_BY_TAG" "$(jq -r .managedBy <<<"$workspace_json")"

workspace_id="$(az monitor log-analytics workspace show \
  --resource-group "$RESOURCE_GROUP" \
  --workspace-name "$WORKSPACE_NAME" \
  --query id \
  --output tsv \
  --only-show-errors)"
app_insights_id="$(az resource show \
  --resource-group "$RESOURCE_GROUP" \
  --resource-type Microsoft.Insights/components \
  --name "$APP_INSIGHTS_NAME" \
  --query id \
  --output tsv \
  --only-show-errors)"
app_insights_workspace="$(az resource show \
  --resource-group "$RESOURCE_GROUP" \
  --resource-type Microsoft.Insights/components \
  --name "$APP_INSIGHTS_NAME" \
  --query properties.WorkspaceResourceId \
  --output tsv \
  --only-show-errors)"
workspace_id_lower="$(printf '%s' "$workspace_id" | tr '[:upper:]' '[:lower:]')"
app_insights_workspace_lower="$(printf '%s' "$app_insights_workspace" | tr '[:upper:]' '[:lower:]')"
assert_equal "Application Insights workspace link" "$workspace_id_lower" "$app_insights_workspace_lower"

connection_url="https://management.azure.com/subscriptions/${subscription_id}/resourceGroups/${RESOURCE_GROUP}/providers/Microsoft.CognitiveServices/accounts/${ACCOUNT_NAME}/connections/${APP_INSIGHTS_CONNECTION_NAME}?api-version=${CONNECTION_API_VERSION}"
connection_json="$(az rest \
  --method get \
  --url "$connection_url" \
  --query '{category:properties.category,authType:properties.authType,target:properties.target,isShared:properties.isSharedToAll}' \
  --output json \
  --only-show-errors)"
assert_equal "Foundry App Insights connection category" "AppInsights" "$(jq -r .category <<<"$connection_json")"
assert_equal "Foundry App Insights connection auth" "ApiKey" "$(jq -r .authType <<<"$connection_json")"
assert_equal "Foundry App Insights connection shared to projects" "true" "$(jq -r .isShared <<<"$connection_json")"
app_insights_id_lower="$(printf '%s' "$app_insights_id" | tr '[:upper:]' '[:lower:]')"
assert_equal "Foundry App Insights connection target" "$app_insights_id_lower" "$(jq -r '.target | ascii_downcase' <<<"$connection_json")"

account_id="$(az cognitiveservices account show \
  --name "$ACCOUNT_NAME" \
  --resource-group "$RESOURCE_GROUP" \
  --query id \
  --output tsv \
  --only-show-errors)"
project_principal_id="$(az cognitiveservices account project show \
  --name "$ACCOUNT_NAME" \
  --resource-group "$RESOURCE_GROUP" \
  --project-name "$PROJECT_NAME" \
  --query identity.principalId \
  --output tsv \
  --only-show-errors)"

assert_nonzero "caller Foundry User RBAC" "$(az role assignment list --assignee-object-id "$principal_object_id" --role 'Foundry User' --scope "$account_id" --query 'length(@)' --output tsv --only-show-errors)"
assert_nonzero "project identity Foundry User RBAC" "$(az role assignment list --assignee-object-id "$project_principal_id" --role 'Foundry User' --scope "$account_id" --query 'length(@)' --output tsv --only-show-errors)"
assert_nonzero "caller Monitoring Reader RBAC" "$(az role assignment list --assignee-object-id "$principal_object_id" --role 'Monitoring Reader' --scope "$app_insights_id" --query 'length(@)' --output tsv --only-show-errors)"
assert_nonzero "project Monitoring Metrics Publisher RBAC" "$(az role assignment list --assignee-object-id "$project_principal_id" --role 'Monitoring Metrics Publisher' --scope "$app_insights_id" --query 'length(@)' --output tsv --only-show-errors)"

info "Verification completed: ${pass_count} checks passed. No billable inference request was sent."
print_application_configuration
