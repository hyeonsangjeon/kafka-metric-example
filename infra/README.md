# Azure Foundry Model Router infrastructure

This directory provisions the dedicated, non-production Azure resources for the
Kafka Model Router demo. The workflow is intentionally CLI-driven, repeatable,
tagged, and bounded by one resource group.

## Demo configuration

| Setting | Value |
| --- | --- |
| Region | `eastus2` |
| Resource group | `rg-kafka-router-${AZURE_UNIQUE_SUFFIX}` |
| Foundry resource | `aif-kafka-router-${AZURE_UNIQUE_SUFFIX}` (`AIServices`, system identity) |
| Foundry project | `kafka-router-demo` (system identity) |
| Fixed deployment | `gpt-5.4-mini` → `gpt-5.4-mini` `2026-03-17` |
| Default router | `model-router-default` → `model-router` `2025-11-18`, `balanced`, all supported models |
| Advanced router | `model-router-cost` → `model-router` `2025-11-18`, `cost` |
| Advanced subset | `gpt-5.4-nano` `2026-03-17`, `gpt-5.4-mini` `2026-03-17`, `gpt-5.4` `2026-03-05` |
| Model deployment SKU | `GlobalStandard`, capacity `10` each |
| Log Analytics | `law-kafka-router-${AZURE_UNIQUE_SUFFIX}`, `0.1 GB/day`, `30` days |
| Application Insights | `appi-kafka-router-${AZURE_UNIQUE_SUFFIX}`, workspace based |

Set one stable, globally unique suffix before invoking any infrastructure
script. This prevents the public quick start from competing for one hardcoded
Foundry subdomain and keeps later verify/cleanup commands deterministic:

```bash
export AZURE_UNIQUE_SUFFIX='your-unique-lowercase-suffix'
```

The suffix must be 3-20 lowercase letters, digits, or hyphens. Individual names
remain overridable for an existing naming convention:

```bash
export AZURE_LOCATION='eastus2'
export AZURE_RESOURCE_GROUP='rg-my-router-demo'
export AZURE_FOUNDRY_ACCOUNT='globally-unique-foundry-name'
export AZURE_FOUNDRY_PROJECT='model-router-demo'
export AZURE_LOG_WORKSPACE='law-my-router-demo'
export AZURE_APP_INSIGHTS='appi-my-router-demo'
export AZURE_APP_INSIGHTS_CONNECTION='appinsights'
export FOUNDRY_FIXED_DEPLOYMENT='gpt-5.4-mini'
export FOUNDRY_DEFAULT_ROUTER_DEPLOYMENT='model-router-default'
export FOUNDRY_COST_ROUTER_DEPLOYMENT='model-router-cost'
```

No subscription or tenant value is hardcoded. Every Azure CLI call uses the
currently selected `az` account; inspect and set it before `--apply`.

All managed resources carry these tags:

```text
application=kafka-metric-example
environment=demo
managed-by=kafka-metric-example-infra
purpose=model-router-demo
cleanup=resource-group
repository=kafka-metric-example
expires-on=<UTC date seven days after provisioning>
```

Set `AZURE_EXPIRES_ON=YYYY-MM-DD` to override that advisory expiry tag. The tag
does not schedule deletion; cleanup remains an explicit guarded operation.

## Safety and credentials

- `provision.sh` defaults to a local dry run. Azure writes require `--apply`.
- The Foundry resource has local/key authentication disabled. Runtime access is
  through Microsoft Entra ID.
- No script retrieves or prints a Cognitive Services key, bearer token,
  Application Insights connection string, subscription ID, tenant ID, or
  principal ID.
- The ARM template passes the Application Insights connection string directly
  from an ARM `reference()` expression into the encrypted account-level Foundry
  connection. The connection is shared to projects and inherited by the demo
  project; no conflicting project-level connection is created.
  It is never committed, emitted as an output, or written to a local file.
- `verify.sh` is read-only and deliberately does not make billable inference
  calls. Use the application smoke scenarios for data-plane verification.
- Cleanup refuses to delete a resource group unless the expected `managed-by`
  tag is present and an exact confirmation literal is supplied.

## Prerequisites

- Azure CLI 2.87 or newer
- `jq`
- An active Azure CLI login on the intended subscription
- `Owner`, or equivalent resource creation plus RBAC assignment permissions

`--apply` idempotently registers `Microsoft.CognitiveServices`,
`Microsoft.OperationalInsights`, and `microsoft.insights` when required. It does
not unregister them during cleanup because registrations are subscription-wide.

## Provision

Review the immutable plan without contacting Azure:

```bash
export AZURE_UNIQUE_SUFFIX='your-unique-lowercase-suffix'
./infra/provision.sh --dry-run
```

Create or converge the resources and grant the signed-in user demo access:

```bash
./infra/provision.sh --apply
```

For a service principal, group, or a user other than the signed-in user, pass
its Entra object ID. The value is held only in process memory and ARM request
parameters; the scripts never print it.

```bash
./infra/provision.sh --apply --principal-object-id '<object-id>'
```

The deployment uses ARM incremental mode and deterministic role assignment
names, so the same command is safe to rerun. Before deployment it discovers an
existing assignment for each exact scope/principal/role tuple and reuses that
assignment's resource name; a stable SHA-256-derived GUID is used only when the
assignment does not exist. Permissions are never deleted. Foundry account child
writes are serialized as project → fixed model → default router → cost router →
shared App Insights connection to avoid concurrent resource-provider conflicts.
The script refuses to adopt an existing resource group that lacks the ownership
tag or an incompatible Foundry account.

## RBAC

The template creates these least-privilege assignments:

| Principal | Scope | Role |
| --- | --- | --- |
| Selected developer | Foundry resource | `Foundry User` |
| Project managed identity | Foundry resource | `Foundry User` |
| Selected developer | Application Insights | `Monitoring Reader` |
| Project managed identity | Application Insights | `Monitoring Metrics Publisher` |

## Verify

Run the read-only control-plane verification:

```bash
./infra/verify.sh
```

It checks tags, identity, local-auth policy, the project, exact model versions,
router mode and subset, capacity, monitoring limits and linkage, the managed
App Insights connection, and RBAC. Only pass `--principal-object-id` when the
original deployment targeted a different principal.

## Application configuration

Use the default router for the standard comparison:

```bash
FOUNDRY_PROJECT_ENDPOINT="https://aif-kafka-router-${AZURE_UNIQUE_SUFFIX}.services.ai.azure.com/api/projects/kafka-router-demo"
FOUNDRY_MODEL='gpt-5.4-mini'
FOUNDRY_ROUTER_DEFAULT_MODEL='model-router-default'
FOUNDRY_ROUTER_ADVANCED_MODEL='model-router-cost'
FOUNDRY_ROUTER_ADVANCED_PROFILE='cost'
```

These values are resource names, not credentials. Continue to authenticate with
`DefaultAzureCredential`/Azure CLI rather than creating an API key.

## Cleanup

Preview the boundary:

```bash
./infra/cleanup.sh --dry-run
```

Delete the dedicated resource group while retaining Azure's normal soft-delete
recovery window for the Foundry account:

```bash
./infra/cleanup.sh --apply \
  --confirm "DELETE-rg-kafka-router-${AZURE_UNIQUE_SUFFIX}"
```

To permanently purge the soft-deleted Foundry account and release its global
subdomain, opt in explicitly. Purge is irreversible:

```bash
./infra/cleanup.sh --apply --purge \
  --confirm "PURGE-aif-kafka-router-${AZURE_UNIQUE_SUFFIX}"
```

The Log Analytics daily cap limits ingestion but is not a total Azure spending
cap. Model calls and retained telemetry can still incur charges; delete the
resource group after the demo when it is no longer needed.

## References

- [Create a Foundry project](https://learn.microsoft.com/azure/foundry/how-to/create-projects)
- [Deploy and use Model Router](https://learn.microsoft.com/azure/foundry/openai/how-to/model-router)
- [Model deployment ARM schema](https://learn.microsoft.com/azure/templates/microsoft.cognitiveservices/accounts/deployments)
- [Foundry RBAC](https://learn.microsoft.com/azure/foundry/concepts/rbac-foundry)
- [Workspace-based Application Insights](https://learn.microsoft.com/azure/azure-monitor/app/create-workspace-resource)
