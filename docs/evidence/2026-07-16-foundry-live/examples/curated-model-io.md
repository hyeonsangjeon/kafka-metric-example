# Curated model input/output

This is one manually reviewed, synthetic benchmark item captured before the
planned demo resource removal. The three responses are retained because they
make the fixed/default/advanced routing behavior concrete. Request, trace,
evaluation, tenant, subscription, and resource IDs were removed.

The main application does **not** retain response bodies. These examples came
from the ignored local evaluation workspace and were selected for this public
record; the bulk raw results remain excluded.

## Shared input

```text
Explain in at most 120 words why a Kafka consumer group can process partitions
in parallel while preserving order only within each partition.
```

Ground truth: a partition is the ordering unit. A consumer group assigns a
partition to at most one consumer at a time, so partitions can run concurrently
while records within one partition follow offset order.

## Fixed GPT-5.4 mini

- Model: `gpt-5.4-mini-2026-03-17`
- Observed latency: 2,256.58 ms
- Prompt/completion/total tokens: 31 / 104 / 135

> Kafka assigns each partition to at most one consumer in a group at a time, so
> messages from that partition are read and processed in the order they were
> written. Because different partitions are independent, Kafka can assign
> different partitions to different consumers, allowing them to be processed in
> parallel. Ordering is not preserved across partitions because Kafka does not
> coordinate a global order between them; each partition has its own log and
> offset sequence. So the guarantee is: **strict order within a partition,
> parallelism across partitions**.

## Default Balanced router

- Selected model: `grok-4-1-fast-reasoning`
- Observed latency: 5,318.67 ms
- Prompt/completion/total tokens: 25 / 86 / 464

> Kafka topics are divided into ordered partitions for scalability. In a
> consumer group, partitions are assigned to consumers such that each partition
> goes to exactly one consumer, allowing multiple consumers to process different
> partitions in parallel.
>
> Per partition, only one consumer processes messages sequentially (FIFO),
> preserving order within it.
>
> Across partitions, independent consumers process concurrently, so messages
> interleave arbitrarily—no global order is guaranteed, only intra-partition
> order.
>
> (72 words)

## Advanced Cost router

- Selected model: `gpt-5.4-nano-2026-03-17`
- Observed latency: 10,006.65 ms
- Prompt/completion/total tokens: 31 / 121 / 152

> Kafka assigns partitions to consumer instances in a group. Each partition is
> read by only one consumer at a time, so messages from that partition are
> consumed sequentially, preserving their order for that partition. At the same
> time, different partitions can be assigned to different consumers, allowing
> those consumers to read and process their assigned partitions concurrently,
> achieving parallelism. Because cross-partition ordering is not
> guaranteed—partitions are independent logs—events written to different
> partitions may be interleaved in processing order. Thus, Kafka can preserve
> ordering per partition while enabling parallel processing across partitions
> within a consumer group.

Latency and token counts are single observations from separate runs. They are
examples, not a benchmark conclusion.
