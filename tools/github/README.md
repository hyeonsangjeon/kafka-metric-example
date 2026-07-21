# GitHub repository utilities

`traffic_attribution.py` captures the three private repository Traffic API
surfaces needed to distinguish canonical, legacy, other, and unattributed page
views:

- `/traffic/views`
- `/traffic/popular/paths`
- `/traffic/popular/referrers`

It depends only on Python's standard library and the authenticated `gh` CLI.
It never prints or persists an authentication token.

## Live capture

```bash
python3 tools/github/traffic_attribution.py \
  --repository hyeonsangjeon/foundry-stream-lab \
  --legacy-repository hyeonsangjeon/kafka-metric-example \
  --renamed-at 2026-07-17T05:39:55Z \
  --output-json tmp/repository-traffic/snapshot.json \
  --output-markdown tmp/repository-traffic/summary.md
```

`--repository` is both the API target and canonical path prefix. Repeat
`--legacy-repository` if more than one historical slug must be classified.
`--renamed-at` controls whether the rolling UTC window is marked rename
ambiguous.

## Offline analysis

Saved API responses can be analyzed without network access:

```bash
python3 tools/github/traffic_attribution.py \
  --repository hyeonsangjeon/foundry-stream-lab \
  --legacy-repository hyeonsangjeon/kafka-metric-example \
  --renamed-at 2026-07-17T05:39:55Z \
  --views-file /path/to/views.json \
  --paths-file /path/to/paths.json \
  --referrers-file /path/to/referrers.json \
  --output-json /tmp/traffic.json \
  --output-markdown /tmp/traffic.md
```

All three offline files are required together. JSON output contains the raw
responses, classification rows, metric definitions, window metadata, and
limitations. Markdown is a compact operator summary of the same snapshot.

## Tests

```bash
python3 -m unittest discover -s tools/github/tests -p 'test_*.py'
```
