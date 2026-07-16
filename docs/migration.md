# 2019 demo to 2026 lab

This is an intentionally breaking rewrite. Git history preserves the original
example; no compatibility adapter is shipped in the production path.

| Original concept | Foundry Stream Lab |
| --- | --- |
| `MetricsVerticle` samples process CPU/memory | Workload runner emits privacy-safe AI lifecycle events |
| Embedded Debezium test Kafka + ZooKeeper | External Apache Kafka 4.3 in single-node KRaft mode |
| `the_topic` with unversioned nested JSON | `foundry.telemetry.v1` with a strict envelope |
| Merge all values into an unbounded object | Bounded, idempotent projection keyed by event ID |
| SockJS event-bus bridge every 300ms | Versioned one-way Server-Sent Events |
| Vendored jQuery/Bootstrap/Highcharts theme | Typed React/Vite interface with code-native visuals |
| Hard-coded localhost configuration | Environment-based, validated configuration |
| Fake infrastructure dashboard values | Clearly labelled simulated or live measurements |

## Removed legacy contract

The old producer wrote the following shape to `the_topic`:

```json
{
  "<pid>": {
    "CPU": 0.42,
    "Mem": 123456789
  }
}
```

The legacy shape has no schema version, timestamp, units, bounded identifier,
or safe extension mechanism. It is documented here for archaeology only and is
never accepted by the new core.

## Provenance

The original dashboard was derived from the Apache Vert.x
`vertx-examples/kafka-examples` project and included third-party UI assets. The
rewrite carries forward the Apache-2.0 license and `NOTICE`, but copies none of
the legacy JavaScript, Java source, templates, images, or fonts.
