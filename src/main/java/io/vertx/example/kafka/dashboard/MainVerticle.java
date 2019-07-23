package io.vertx.example.kafka.dashboard;

import io.debezium.kafka.KafkaCluster;
import io.debezium.util.Testing;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.example.kafka.Runner;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;

import java.io.File;
import java.util.Map;

public class MainVerticle extends AbstractVerticle {

  public static void main(String[] args) {
    Vertx vertx = Vertx.vertx();
    vertx.deployVerticle(new MainVerticle());
//    Runner.runExample(MainVerticle.class);
    System.out.println("MainVerticle RUN " );
  }

  private KafkaCluster kafkaCluster;

  @Override
  public void start() throws Exception {

    // Kafka setup for the example
    File dataDir = Testing.Files.createTestingDirectory("/cluster");
    dataDir.deleteOnExit();
    kafkaCluster = new KafkaCluster()
      .usingDirectory(dataDir)
      .withPorts(2181, 9092)
      .addBrokers(1)
      .deleteDataPriorToStartup(true)
      .startup();

  }

  @Override
  public void stop() throws Exception {
    kafkaCluster.shutdown();
  }
}
