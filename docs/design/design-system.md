# Dashboard design system

The implementation is based on these generated product-design references:

- [`dashboard-concept-desktop.png`](./dashboard-concept-desktop.png)
- [`dashboard-concept-mobile.png`](./dashboard-concept-mobile.png)
- [`model-router-concept-desktop.png`](./model-router-concept-desktop.png)

These concepts are design references, not screenshots of measured production
traffic. The shipped UI uses code-native controls, labels, charts, and events.

## Visual direction

An engineering console with oscilloscope-like stream rails: precise, quiet, and
technical without cyberpunk glow or generic AI gradients.

| Token | Value | Role |
| --- | --- | --- |
| Canvas | `#0b0f14` | True dark application background |
| Surface | `#111821` | Primary working regions |
| Raised | `#162130` | Selected and interactive regions |
| Divider | `#263240` | One-pixel structural boundaries |
| Text | `#f3f7fa` | Primary content |
| Muted | `#aab6c2` | Secondary labels |
| Healthy | `#55d6c2` | Successful request/delivery |
| Warning | `#f5c66a` | Lag, retry, or degraded freshness |
| Failure | `#ff7d76` | Terminal failure |
| Selection | `#83a8ff` | Focus, active navigation, selected trace |

Use a UI sans stack headed by Geist/Inter and a monospace stack for trace IDs,
offsets, timestamps, and tabular metrics. Radii stay between 8 and 12 pixels;
shadows are minimal; nested cards are avoided.

## Primary screen inventory

1. App navigation: Live Lab, Traces, System.
2. Connection rail: AI provider, Kafka transport, live stream.
3. Run console: prompt, workload, traffic, scenario, run/stop/reset actions.
4. KPI rail: completed, success, p95 AI latency, p95 freshness, lag, duplicates.
5. Telemetry River: request path plus four Kafka partition lanes.
6. Live event feed and recent traces.
7. Trace detail drawer with lifecycle waterfall and delivery observation.
8. System map that explicitly separates request and telemetry paths.

## Allowed first-viewport copy

- `Foundry Stream Lab`
- `Live Lab`, `Traces`, `System`
- `Run console`, `Run workload`, `Stop run`, `Reset session`
- `Workload`, `Traffic`, `Failure scenario`
- `Completed`, `Success rate`, `P95 AI latency`, `P95 freshness`,
  `Consumer lag`, `Duplicates filtered`
- `Telemetry River`, `Live events`, `Recent traces`
- `Prompt and response bodies are never published to Kafka.`

No marketing hero, decorative eyebrow, fabricated health score, or unlabelled
fake production metric is permitted.

## Responsive behavior

- Desktop uses a slim left rail and a wide telemetry canvas.
- Mobile replaces the rail with a compact header and bottom navigation.
- KPIs scroll horizontally; the Telemetry River remains a rail visualization
  instead of collapsing into cards.
- Trace detail becomes a full-screen sheet with focus trapping and focus return.
- Touch targets are at least 44 pixels and all status changes have text/icon
  redundancy in addition to color.

## Motion

New ticks reveal in 160-200ms. Backlog drains by consuming ticks rather than
moving an entire lane. Motion is disabled under `prefers-reduced-motion`.
