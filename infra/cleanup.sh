#!/usr/bin/env bash

set -Eeuo pipefail
IFS=$'\n\t'

# shellcheck source=lib.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

usage() {
  cat <<EOF
Usage:
  ./infra/cleanup.sh --dry-run
  ./infra/cleanup.sh --apply --confirm DELETE-${RESOURCE_GROUP}
  ./infra/cleanup.sh --apply --purge --confirm PURGE-${ACCOUNT_NAME}

--dry-run  Print the deletion boundary without changing Azure.
--apply    Delete the dedicated resource group after validating its ownership tag.
--purge    Also permanently purge the soft-deleted Foundry account and release its name.
--confirm  Required literal guard shown above.

Provider registrations are subscription-level and intentionally remain registered.
Use the same required AZURE_UNIQUE_SUFFIX value used for provisioning.
EOF
}

mode="dry-run"
purge="false"
confirmation=""

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
    --purge)
      purge="true"
      shift
      ;;
    --confirm)
      (($# >= 2)) || die "--confirm requires the literal guard value."
      confirmation="$2"
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
  cat <<EOF
Azure write operations are disabled in this dry run.

Delete resource group: ${RESOURCE_GROUP}
Includes:              Foundry account/project, three model deployments,
                       App Insights, Log Analytics, scoped RBAC, deployment history
Purge account:         ${purge}
Retained:              subscription-level provider registrations
EOF
  exit 0
fi

require_tools
require_azure_login

expected_confirmation="DELETE-${RESOURCE_GROUP}"
if [[ "$purge" == "true" ]]; then
  expected_confirmation="PURGE-${ACCOUNT_NAME}"
fi
[[ "$confirmation" == "$expected_confirmation" ]] \
  || die "Confirmation mismatch. Expected: ${expected_confirmation}"

if resource_group_exists; then
  managed_by="$(az group show \
    --name "$RESOURCE_GROUP" \
    --query 'tags."managed-by"' \
    --output tsv \
    --only-show-errors)"
  [[ "$managed_by" == "$MANAGED_BY_TAG" ]] \
    || die "Refusing to delete a resource group without managed-by=${MANAGED_BY_TAG}."

  info "Deleting dedicated resource group ${RESOURCE_GROUP}"
  az group delete \
    --name "$RESOURCE_GROUP" \
    --yes \
    --output none \
    --only-show-errors
else
  info "Resource group ${RESOURCE_GROUP} is already absent."
fi

if [[ "$purge" == "true" ]]; then
  info "Permanently purging soft-deleted Foundry account ${ACCOUNT_NAME}"
  az cognitiveservices account purge \
    --name "$ACCOUNT_NAME" \
    --resource-group "$RESOURCE_GROUP" \
    --location "$LOCATION" \
    --output none \
    --only-show-errors
fi

info "Cleanup completed."
