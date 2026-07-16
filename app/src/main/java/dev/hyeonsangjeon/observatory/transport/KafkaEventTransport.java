package dev.hyeonsangjeon.observatory.transport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hyeonsangjeon.observatory.config.AppConfig;
import dev.hyeonsangjeon.observatory.model.TelemetryEvent;
import dev.hyeonsangjeon.observatory.util.Hashing;
import io.vertx.core.json.jackson.DatabindCodec;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.CloseOptions;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

public final class KafkaEventTransport implements EventTransport {
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(250);

    private final String topic;
    private final String dlqTopic;
    private final ObjectMapper mapper = DatabindCodec.mapper().copy();
    private final KafkaProducer<String, String> producer;
    private final KafkaConsumer<String, String> kafkaConsumer;
    private final AdminClient adminClient;
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final AtomicBoolean brokerHealthy = new AtomicBoolean(false);
    private volatile Thread consumerThread;
    private volatile Thread healthThread;

    public KafkaEventTransport(AppConfig config) {
        this.topic = config.kafkaTopic();
        this.dlqTopic = config.kafkaDlqTopic();

        Properties producerProperties = new Properties();
        producerProperties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.kafkaBootstrapServers());
        producerProperties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProperties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProperties.put(ProducerConfig.ACKS_CONFIG, "all");
        producerProperties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        producerProperties.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        producerProperties.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10_000);
        producerProperties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
        producerProperties.put(ProducerConfig.CLIENT_ID_CONFIG, "foundry-observatory-producer");
        this.producer = new KafkaProducer<>(producerProperties);

        Properties consumerProperties = new Properties();
        consumerProperties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.kafkaBootstrapServers());
        consumerProperties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProperties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProperties.put(ConsumerConfig.GROUP_ID_CONFIG, "foundry-observatory-" + UUID.randomUUID());
        consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        consumerProperties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        consumerProperties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 250);
        consumerProperties.put(ConsumerConfig.CLIENT_ID_CONFIG, "foundry-observatory-consumer");
        this.kafkaConsumer = new KafkaConsumer<>(consumerProperties);

        Properties adminProperties = new Properties();
        adminProperties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, config.kafkaBootstrapServers());
        adminProperties.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 2_000);
        adminProperties.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 2_000);
        adminProperties.put(AdminClientConfig.CLIENT_ID_CONFIG, "foundry-observatory-health");
        this.adminClient = AdminClient.create(adminProperties);
    }

    public static void verifyBroker(AppConfig config, Duration timeout)
            throws InterruptedException, ExecutionException, TimeoutException {
        Properties properties = new Properties();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, config.kafkaBootstrapServers());
        properties.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, Math.toIntExact(timeout.toMillis()));
        properties.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, Math.toIntExact(timeout.toMillis()));
        properties.put(AdminClientConfig.CLIENT_ID_CONFIG, "foundry-observatory-readiness");
        try (AdminClient admin = AdminClient.create(properties)) {
            admin.describeCluster().clusterId().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public String name() {
        return "kafka";
    }

    @Override
    public void start(Consumer<EventDelivery> consumer) {
        if (consumerThread != null) {
            throw new IllegalStateException("transport already started");
        }
        kafkaConsumer.subscribe(List.of(topic));
        consumerThread = Thread.ofPlatform()
                .name("kafka-telemetry-consumer")
                .daemon(true)
                .start(() -> consume(consumer));
        healthThread = Thread.ofPlatform()
                .name("kafka-telemetry-health")
                .daemon(true)
                .start(this::monitorBroker);
    }

    @Override
    public CompletableFuture<Void> publish(TelemetryEvent event) {
        if (!open.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("transport is closed"));
        }
        try {
            String payload = mapper.writeValueAsString(event);
            ProducerRecord<String, String> record = event.partitionHint() == null
                    ? new ProducerRecord<>(topic, routingKey(event), payload)
                    : new ProducerRecord<>(topic, event.partitionHint(), routingKey(event), payload);
            CompletableFuture<Void> result = new CompletableFuture<>();
            producer.send(record, (metadata, exception) -> {
                if (exception == null) {
                    brokerHealthy.set(true);
                    result.complete(null);
                } else {
                    brokerHealthy.set(false);
                    result.completeExceptionally(exception);
                }
            });
            return result;
        } catch (JsonProcessingException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private void consume(Consumer<EventDelivery> consumer) {
        while (open.get()) {
            try {
                ConsumerRecords<String, String> records = kafkaConsumer.poll(POLL_TIMEOUT);
                Map<TopicPartition, Long> endOffsets = records.isEmpty()
                        ? Map.of()
                        : kafkaConsumer.endOffsets(kafkaConsumer.assignment());
                for (ConsumerRecord<String, String> record : records) {
                    handleRecord(record, endOffsets, consumer);
                }
                if (!records.isEmpty()) {
                    kafkaConsumer.commitAsync();
                }
            } catch (org.apache.kafka.common.errors.WakeupException exception) {
                if (open.get()) {
                    throw exception;
                }
            } catch (RuntimeException exception) {
                // Polling resumes so transient broker failures do not kill observability.
                brokerHealthy.set(false);
                if (!open.get()) {
                    return;
                }
            }
        }
    }

    private void handleRecord(
            ConsumerRecord<String, String> record,
            Map<TopicPartition, Long> endOffsets,
            Consumer<EventDelivery> consumer) {
        try {
            TelemetryEvent event = mapper.readValue(record.value(), TelemetryEvent.class);
            if ("consumer_slowdown".equals(event.scenario())) {
                // Intentional fault injection: slow the real projection consumer, allowing broker lag to build.
                LockSupport.parkNanos(Duration.ofMillis(180).toNanos());
            }
            TopicPartition partition = new TopicPartition(record.topic(), record.partition());
            long lag = Math.max(0L, endOffsets.getOrDefault(partition, record.offset() + 1) - record.offset() - 1);
            consumer.accept(new EventDelivery(event, record.partition(), record.offset(), lag));
        } catch (RuntimeException | JsonProcessingException exception) {
            publishSanitizedDlq(record.value());
        }
    }

    private void publishSanitizedDlq(String rejectedPayload) {
        String safeValue = rejectedPayload == null ? "" : rejectedPayload;
        String dlq = "{\"schema_version\":\"1.0\",\"error_code\":\"INVALID_ENVELOPE\"," +
                "\"payload_hash\":\"" + Hashing.sha256(safeValue) + "\"," +
                "\"payload_chars\":" + safeValue.length() + "}";
        producer.send(new ProducerRecord<>(dlqTopic, "invalid-envelope", dlq));
    }

    private void monitorBroker() {
        while (open.get()) {
            try {
                adminClient.describeCluster().clusterId().get(2, TimeUnit.SECONDS);
                brokerHealthy.set(true);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException | TimeoutException | RuntimeException exception) {
                brokerHealthy.set(false);
            }
            LockSupport.parkNanos(Duration.ofSeconds(3).toNanos());
        }
    }

    private static String routingKey(TelemetryEvent event) {
        return event.traceId() == null ? event.runId() : event.traceId();
    }

    @Override
    public boolean healthy() {
        return open.get()
                && brokerHealthy.get()
                && consumerThread != null
                && consumerThread.isAlive()
                && healthThread != null
                && healthThread.isAlive();
    }

    @Override
    public void close() {
        if (!open.compareAndSet(true, false)) {
            return;
        }
        kafkaConsumer.wakeup();
        Thread health = healthThread;
        if (health != null) {
            health.interrupt();
        }
        Thread thread = consumerThread;
        if (thread != null) {
            try {
                thread.join(2_000L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        if (health != null) {
            try {
                health.join(2_000L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        kafkaConsumer.close(CloseOptions.timeout(Duration.ofSeconds(2)));
        producer.close(Duration.ofSeconds(2));
        adminClient.close(Duration.ofSeconds(2));
    }
}
