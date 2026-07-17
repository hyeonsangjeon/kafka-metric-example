# Curated model input/output

This is one manually reviewed synthetic input sent separately to the same fixed,
default-router, and advanced-router deployments. It makes the routing behavior
concrete while the application itself continues to discard provider response
bodies. Request, response, trace, evaluation, tenant, subscription, and resource
IDs were removed. Bulk raw evaluation results remain in an ignored workspace.

## Shared input

```text
Explain one practical Kafka reliability signal in two concise sentences.
```

## Fixed GPT-5.4 mini

- Selected model: `gpt-5.4-mini-2026-03-17`
- Observed latency: 3,223.77 ms
- Input/output/total tokens: 17 / 57 / 74

> One practical Kafka reliability signal is **consumer lag**: how far behind
> consumers are from the latest messages in a partition. If lag keeps growing
> or stays high, it often means consumers are unhealthy, under-provisioned, or
> unable to keep up with incoming traffic.

## Default Balanced router

- Selected model: `grok-4-1-fast-reasoning`
- Observed latency: 2,525.51 ms
- Input/output/total tokens: 11 / 59 / 286

> **Under-replicated partitions** is a Kafka metric counting partitions with
> fewer in-sync replicas (ISR) than the configured replication factor,
> signaling potential data loss risks due to broker failures. Monitoring and
> alerting on non-zero values prompts actions like adding brokers or rebalancing
> to restore full replication and high availability.

## Advanced Cost router

- Selected model: `gpt-5.4-nano-2026-03-17`
- Observed latency: 2,553.79 ms
- Input/output/total tokens: 17 / 84 / 101

> A practical Kafka reliability signal is **consumer lag**: if the consumer
> group’s lag consistently grows, it indicates the system can’t keep up,
> risking delayed processing or eventual downtime under sustained load. Another
> signal is **under-replicated partitions / replication errors**: if replicas
> fall behind or go under-replicated, it reduces fault tolerance and increases
> the chance of data unavailability during broker failures.

These are single observations, not a benchmark conclusion. The default
router's total includes provider token accounting beyond visible input and
output tokens.
