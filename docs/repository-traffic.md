# Repository traffic attribution

GitHub repository traffic is a short operational signal, not a product
analytics system. This repository was renamed from
`hyeonsangjeon/kafka-metric-example` to
`hyeonsangjeon/foundry-stream-lab` on 2026-07-17. GitHub redirects the old web
paths, while the [Traffic API](https://docs.github.com/en/rest/metrics/traffic?apiVersion=2022-11-28)
reports a rolling 14-day window and only the top 10 popular paths. A snapshot
taken near the rename can therefore contain both slugs without representing
traffic from two independent repositories. See GitHub's
[repository rename behavior](https://docs.github.com/en/repositories/creating-and-managing-repositories/renaming-a-repository)
for the redirect contract.

Use [`tools/github/traffic_attribution.py`](../tools/github/traffic_attribution.py)
to preserve the raw API response and classify each returned path. The tool does
not modify repository settings, commit generated data, or sum unique visitors
across paths.

## Metric contract

| Metric | Meaning |
| --- | --- |
| `total_views` | Complete view count returned by `/traffic/views` for GitHub's rolling window. |
| `unique_visitors` | Window-level unique count from `/traffic/views`; this is the authoritative unique metric. |
| `canonical_known_top_path_views` | Views among returned top paths whose exact repository prefix is `/hyeonsangjeon/foundry-stream-lab`. |
| `legacy_known_top_path_views` | Views among returned top paths whose exact repository prefix is `/hyeonsangjeon/kafka-metric-example`. |
| `other_known_top_path_views` | Views in returned top paths that belong to neither configured prefix. |
| `not_in_top_paths_views` | `total_views` minus the sum of returned top paths; these views cannot be attributed by this API. |
| `top_path_coverage` | Returned top-path views divided by `total_views`; `1.0` means the returned rows cover this snapshot, not that the endpoint is generally complete. |

The `*_known_top_path_views` names are deliberate. Popular paths is capped at
10 rows, so canonical and legacy counts can be lower bounds. Only when
`not_in_top_paths_views` is zero does the path split cover the complete total
for that snapshot.

Path-level `uniques` are retained only as raw row metadata. The same visitor can
appear on several paths, so those values must never be added together.

## Rename transition baseline

The snapshot captured at `2026-07-21T16:12:09Z` returned:

- 11 total views from one unique visitor;
- 9 legacy known-top-path views and 2 canonical known-top-path views;
- zero views outside the five returned popular paths;
- 10 views on 2026-07-17 UTC and 1 view on 2026-07-19 UTC;
- `github.com` as the referrer for all 11 views.

This does not support interpreting “11 views” as 11 users showing interest in
the rebuilt content. It is a rename-attribution snapshot from one unique
visitor. GitHub does not expose a path-by-date cross-tab, so the 2026-07-17
views cannot be divided into pre-rename and post-redirect requests.

The current README, workflows, container references, OCI source label, and Git
remote use the canonical name. Remaining old-name strings are an intentional
cleanup ownership sentinel or immutable historical evidence. Do not rewrite
those records to make a rolling traffic report look cleaner.

The audit also issued HTTP `HEAD` requests to old URLs after capturing the
snapshot. GitHub does not document whether those probes count as views. Use a
snapshot taken on or after 2026-08-06 KST as the first clean post-audit check.

## Local capture

The live path uses the authenticated GitHub CLI. It requires repository
administration read access because that is the permission required by the
Traffic API.

```bash
gh auth status
make repository-traffic
```

The command writes ignored, ephemeral outputs to:

- `tmp/repository-traffic/snapshot.json`
- `tmp/repository-traffic/summary.md`

Run only the transformation tests with:

```bash
make repository-traffic-test
```

For a fully offline reproduction, pass `--views-file`, `--paths-file`, and
`--referrers-file` to the Python tool. See
[`tools/github/README.md`](../tools/github/README.md) for the exact interface.

## Automation boundary

This public repository intentionally does not run the capture in GitHub
Actions. The workflow `GITHUB_TOKEN` cannot receive the Traffic API's
`Administration: read` permission, and supplying a separate token would make
the otherwise administrator-only raw paths and referrers visible through a
public run summary or artifact.

Keep live snapshots local and ignored. If long-term automation becomes
necessary, send the result to a separately reviewed private store using a
GitHub App or fine-grained token limited to this repository and
`Administration: Read-only`. Do not commit snapshots to `master`, copy a broad
local OAuth token into Actions, or publish raw referrers merely to retain a
history.

## Interpretation rules

- Report total, canonical-known, legacy-known, other-known, and unattributed
  views separately.
- Exclude rename-ambiguous totals from claims about interest in the canonical
  content.
- Treat a continuing legacy count after the clean-check date as redirect
  traffic, then inspect releases and external backlinks without changing
  historical evidence.
- Treat canonical-known views as confirmed canonical-path traffic, not as a
  user count and not automatically as positive engagement.
- Keep the raw snapshot with every published interpretation so the top-path
  coverage and unique denominator remain auditable.
