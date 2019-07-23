package io.vertx.example.kafka.dashboard;

import com.sun.management.OperatingSystemMXBean;
import com.sun.management.ThreadMXBean;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.example.kafka.Runner;
import io.vertx.kafka.client.producer.KafkaWriteStream;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.UUID;

public class MetricsVerticle extends AbstractVerticle {

  public static void main(String[] args) {
//    VertxOptions options = new VertxOptions();
//    options.setWorkerPoolSize(16); //worker pool size
//    Vertx vertx = Vertx.vertx(options);
//    vertx.deployVerticle(new MetricsVerticle());
//    System.out.println("MetricsVerticle RUN " );

  }
  private OperatingSystemMXBean systemMBean;
  private KafkaWriteStream<String, JsonObject> producer;

  @Override
  public void start() throws Exception {
    systemMBean = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);

    // Process Runtime resource
    RuntimeMXBean runbean = ManagementFactory.getRuntimeMXBean();
    long bfprocesstime = systemMBean.getProcessCpuTime();
    long bfuptime = runbean.getUptime();
    long ncpus = systemMBean.getAvailableProcessors();

    for (int i = 0; i < 1000000; ++i) {
      ncpus = systemMBean.getAvailableProcessors();
    }

    long afprocesstime = systemMBean.getProcessCpuTime();
    long afuptime = runbean.getUptime();

    float cal = (afprocesstime - bfprocesstime)
            / ((afuptime - bfuptime) * 10000f);

    float usage = Math.min(99f, cal);


    String pid = runbean.getName();
    System.out.println("PID = " + pid);

    // Get the kafka producer config
    JsonObject config = config();
    config.put("bootstrap.servers", "127.0.0.1:9092");
    config.put("zookeeper.connect", "127.0.0.1:2181");


    // Create the producer
    producer = KafkaWriteStream.create(vertx, config.getMap(), String.class, JsonObject.class);

    // Publish the metircs in Kafka
    vertx.setPeriodic(300, id -> {
      JsonObject metrics = new JsonObject();
      metrics.put("CPU", systemMBean.getProcessCpuLoad());
//      System.out.println("usage "+cal);
//      metrics.put("CPU", cal);
      metrics.put("Mem", systemMBean.getTotalPhysicalMemorySize() - systemMBean.getFreePhysicalMemorySize());
//      System.out.println("metrics :"+metrics.encodePrettily() );
      producer.write(new ProducerRecord<>("the_topic", new JsonObject().put(pid, metrics)));
    });
  }

  @Override
  public void stop() throws Exception {
    if (producer != null) {
      producer.close();
    }
  }
}
