#!/usr/bin/env bash

set -Eeuo pipefail
IFS=$'\n\t'

# shellcheck source=lib.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

usage() {
  cat <<EOF
Usage:
  AZURE_UNIQUE_SUFFIX=your-unique-suffix ./infra/provision.sh --dry-run
  AZURE_UNIQUE_SUFFIX=your-unique-suffix ./infra/provision.sh --apply [--principal-object-id UUID]

--dry-run              Print the immutable plan. Performs no Azure write operation.
--apply                Register required providers and idempotently deploy the plan.
--principal-object-id  Grant access to this Entra object. Defaults to the signed-in user.
--help                 Show this help.

The script never retrieves account keys and suppresses ARM deployment output.
AZURE_UNIQUE_SUFFIX is required and derives globally collision-resistant resource names.
EOF
}

mode="dry-run"
principal_object_id=""

deterministic_guid() {
  local seed="$1"
  local digest

  if command -v sha256sum >/dev/null 2>&1; then
    digest="$(printf '%s' "$seed" | sha256sum | awk '{print $1}')"
  elif command -v shasum >/dev/null 2>&1; then
    digest="$(printf '%s' "$seed" | shasum -a 256 | awk '{print $1}')"
  else
    die "A SHA-256 utility (sha256sum or shasum) is required."
  fi

  printf '%s-%s-%s-%s-%s' \
    "${digest:0:8}" \
    "${digest:8:4}" \
    "${digest:12:4}" \
    "${digest:16:4}" \
    "${digest:20:12}"
}

role_assignment_name() {
  local scope="$1"
  local principal_id="$2"
  local role_name="$3"
  local fallback_identity="$4"
  local scope_exists="$5"
  local assignments
  local existing

  if [[ "$scope_exists" == "true" && -n "$principal_id" ]]; then
    assignments="$(az role assignment list \
      --assignee-object-id "$principal_id" \
      --role "$role_name" \
      --scope "$scope" \
      --output json \
      --only-show-errors)"
    existing="$(jq -r \
      --arg scope "$scope" \
      '[.[] | select((.scope | ascii_downcase) == ($scope | ascii_downcase)) | .name][0] // empty' \
      <<<"$assignments")"
    if [[ -n "$existing" ]]; then
      printf '%s' "$existing"
      return
    fi
  fi

  deterministic_guid "${scope}|${fallback_identity}|${role_name}"
}

while (($#)); do
  case "$1" in
    --dry-run)
      mode="dry-run"
      shift
      ;;
    --apply)
      mode="apply"
      shift
      ;;
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

require_unique_suffix

if [[ "$mode" == "dry-run" ]]; then
  print_plan
  exit 0
fi

require_tools
require_azure_login
[[ -f "$TEMPLATE_FILE" ]] || die "ARM template not found: $TEMPLATE_FILE"

principal_object_id="$(resolve_principal_object_id "$principal_object_id")"
subscription_id="$(az account show --query id --output tsv --only-show-errors)"

for provider in Microsoft.CognitiveServices Microsoft.OperationalInsights microsoft.insights; do
  state="$(az provider show --namespace "$provider" --query registrationState --output tsv --only-show-errors 2>/dev/null || true)"
  if [[ "$state" != "Registered" ]]; then
    info "Registering resource provider ${provider}"
    az provider register \
      --namespace "$provider" \
      --wait \
      --output none \
      --only-show-errors
  fi
done

if resource_group_exists; then
  group_tags="$(az group show \
    --name "$RESOURCE_GROUP" \
    --query '{managedBy:tags."managed-by",purpose:tags.purpose,repository:tags.repository}' \
    --output json \
    --only-show-errors)"
  managed_by="$(jq -r '.managedBy // ""' <<<"$group_tags")"
  group_purpose="$(jq -r '.purpose // ""' <<<"$group_tags")"
  group_repository="$(jq -r '.repository // ""' <<<"$group_tags")"
  if [[ "$managed_by" != "$MANAGED_BY_TAG" ]]; then
    [[ "$managed_by" == "$LEGACY_MANAGED_BY_TAG" \
      && "$group_purpose" == "$PURPOSE_TAG" \
      && "$group_repository" == "$REPOSITORY_TAG" ]] \
      || die "Refusing to adopt an existing resource group without the expected legacy demo tags."
    info "Adopting the existing tagged demo resource group"
  fi

  az group update \
    --name "$RESOURCE_GROUP" \
    --tags \
      application=foundry-stream-lab \
      environment=demo \
      managed-by="$MANAGED_BY_TAG" \
      purpose="$PURPOSE_TAG" \
      cleanup=resource-group \
      repository="$REPOSITORY_TAG" \
      expires-on="$EXPIRES_ON_TAG" \
    --output none \
    --only-show-errors
else
  info "Creating dedicated resource group ${RESOURCE_GROUP}"
  az group create \
    --name "$RESOURCE_GROUP" \
    --location "$LOCATION" \
    --tags \
      application=foundry-stream-lab \
      environment=demo \
      managed-by="$MANAGED_BY_TAG" \
      purpose="$PURPOSE_TAG" \
      cleanup=resource-group \
      repository="$REPOSITORY_TAG" \
      expires-on="$EXPIRES_ON_TAG" \
    --output none \
    --only-show-errors
fi

account_present="false"
if account_exists; then
  account_present="true"
  existing_json="$(az cognitiveservices account show \
    --name "$ACCOUNT_NAME" \
    --resource-group "$RESOURCE_GROUP" \
    --query '{kind:kind,location:location,projects:properties.allowProjectManagement,domain:properties.customSubDomainName}' \
    --output json \
    --only-show-errors)"
  existing_kind="$(jq -r .kind <<<"$existing_json")"
  existing_location="$(jq -r .location <<<"$existing_json")"
  existing_projects="$(jq -r .projects <<<"$existing_json")"
  existing_domain="$(jq -r .domain <<<"$existing_json")"
  [[ "$existing_kind" == "AIServices" ]] || die "Existing account kind is not AIServices."
  [[ "$existing_location" == "$LOCATION" ]] || die "Existing account is not in ${LOCATION}."
  [[ "$existing_projects" == "true" ]] || die "Existing account does not allow project management."
  [[ "$existing_domain" == "$ACCOUNT_NAME" ]] || die "Existing account custom subdomain does not match."
else
  domain_available="$(az rest \
    --method post \
    --url "https://management.azure.com/subscriptions/${subscription_id}/providers/Microsoft.CognitiveServices/checkDomainAvailability?api-version=2026-05-01" \
    --body "{\"subdomainName\":\"${ACCOUNT_NAME}\",\"type\":\"Microsoft.CognitiveServices/accounts\"}" \
    --query isSubdomainAvailable \
    --output tsv \
    --only-show-errors)"
  [[ "$domain_available" == "true" ]] \
    || die "The global Foundry subdomain ${ACCOUNT_NAME} is unavailable."
fi

account_scope="/subscriptions/${subscription_id}/resourceGroups/${RESOURCE_GROUP}/providers/Microsoft.CognitiveServices/accounts/${ACCOUNT_NAME}"
project_resource_id="${account_scope}/projects/${PROJECT_NAME}"
app_insights_scope="/subscriptions/${subscription_id}/resourceGroups/${RESOURCE_GROUP}/providers/Microsoft.Insights/components/${APP_INSIGHTS_NAME}"

project_principal_id=""
if [[ "$account_present" == "true" ]] && az cognitiveservices account project show \
  --name "$ACCOUNT_NAME" \
  --resource-group "$RESOURCE_GROUP" \
  --project-name "$PROJECT_NAME" \
  --output none \
  --only-show-errors >/dev/null 2>&1; then
  project_principal_id="$(az cognitiveservices account project show \
    --name "$ACCOUNT_NAME" \
    --resource-group "$RESOURCE_GROUP" \
    --project-name "$PROJECT_NAME" \
    --query identity.principalId \
    --output tsv \
    --only-show-errors)"
fi

app_insights_present="false"
if az resource show \
  --resource-group "$RESOURCE_GROUP" \
  --resource-type Microsoft.Insights/components \
  --name "$APP_INSIGHTS_NAME" \
  --output none \
  --only-show-errors >/dev/null 2>&1; then
  app_insights_present="true"
fi

developer_foundry_role_name="$(role_assignment_name \
  "$account_scope" "$principal_object_id" "Foundry User" "$principal_object_id" "$account_present")"
project_foundry_role_name="$(role_assignment_name \
  "$account_scope" "$project_principal_id" "Foundry User" "$project_resource_id" "$account_present")"
developer_monitoring_role_name="$(role_assignment_name \
  "$app_insights_scope" "$principal_object_id" "Monitoring Reader" "$principal_object_id" "$app_insights_present")"
project_monitoring_role_name="$(role_assignment_name \
  "$app_insights_scope" "$project_principal_id" "Monitoring Metrics Publisher" "$project_resource_id" "$app_insights_present")"

info "Deploying Foundry, Model Router, monitoring, and RBAC resources"
tags_json="$(jq -cn \
  --arg managedBy "$MANAGED_BY_TAG" \
  --arg purpose "$PURPOSE_TAG" \
  --arg repository "$REPOSITORY_TAG" \
  --arg expiresOn "$EXPIRES_ON_TAG" \
  '{application:"foundry-stream-lab",environment:"demo","managed-by":$managedBy,purpose:$purpose,cleanup:"resource-group",repository:$repository,"expires-on":$expiresOn}')"
az deployment group create \
  --name "$DEPLOYMENT_NAME" \
  --resource-group "$RESOURCE_GROUP" \
  --mode Incremental \
  --template-file "$TEMPLATE_FILE" \
  --parameters \
    location="$LOCATION" \
    accountName="$ACCOUNT_NAME" \
    projectName="$PROJECT_NAME" \
    workspaceName="$WORKSPACE_NAME" \
    appInsightsName="$APP_INSIGHTS_NAME" \
    appInsightsConnectionName="$APP_INSIGHTS_CONNECTION_NAME" \
    fixedDeploymentName="$FIXED_DEPLOYMENT" \
    defaultRouterDeploymentName="$ROUTER_DEFAULT_DEPLOYMENT" \
    costRouterDeploymentName="$ROUTER_COST_DEPLOYMENT" \
    principalObjectId="$principal_object_id" \
    developerFoundryUserRoleAssignmentName="$developer_foundry_role_name" \
    projectFoundryUserRoleAssignmentName="$project_foundry_role_name" \
    developerMonitoringReaderRoleAssignmentName="$developer_monitoring_role_name" \
    projectMonitoringPublisherRoleAssignmentName="$project_monitoring_role_name" \
    tags="$tags_json" \
  --output none \
  --only-show-errors

info "Provisioning completed. Running read-only verification."
"${INFRA_DIR}/verify.sh" --principal-object-id "$principal_object_id"
print_application_configuration
