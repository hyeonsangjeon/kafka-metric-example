package io.vertx.example.kafka.dashboard;

import io.debezium.kafka.KafkaCluster;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.example.kafka.Runner;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.ext.web.handler.sockjs.BridgeOptions;
import io.vertx.ext.web.handler.sockjs.PermittedOptions;
import io.vertx.ext.web.handler.sockjs.SockJSHandler;
import io.vertx.kafka.client.consumer.KafkaReadStream;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;

import java.util.Collections;
import java.util.Map;
public class DashboardVerticle extends AbstractVerticle {


  public static void main(String[] args) {
//
//    Vertx vertx = Vertx.vertx();
//    JsonObject consumerConfig = new JsonObject();
//    consumerConfig.put("auto.offset.reset","latest");
//    consumerConfig.put("bootstrap.servers","127.0.0.1:9092");
//    consumerConfig.put("enable.auto.commit","false");
//    consumerConfig.put("group.id","the_group");
//    consumerConfig.put("client.id","the_client");
//    vertx.deployVerticle(DashboardVerticle.class.getName(),
//            new DeploymentOptions().setConfig(consumerConfig));
//    System.out.println("DashboardVerticle RUN " );

  }

  @Override
  public void start() throws Exception {

    Router router = Router.router(vertx);

    // The event bus bridge handler
    BridgeOptions options = new BridgeOptions();
    options.setOutboundPermitted(Collections.singletonList(new PermittedOptions().setAddress("dashboard")));
    router.route("/eventbus/*").handler(SockJSHandler.create(vertx).bridge(options));

    // The web server handler
    router.route().handler(StaticHandler.create().setCachingEnabled(false));

    // Start http server
    HttpServer httpServer = vertx.createHttpServer();
    httpServer.requestHandler(router::accept).listen(8080, ar -> {
      if (ar.succeeded()) {
        System.out.println("Http server started");
      } else {
        ar.cause().printStackTrace();
      }
    });


    // Our dashboard that aggregates metrics from various kafka topics
    JsonObject dashboard = new JsonObject();

    // Publish the dashboard to the browser over the bus
    vertx.setPeriodic(300, timerID -> {
      vertx.eventBus().publish("dashboard", dashboard);
    });

    // Get the Kafka consumer config
    JsonObject config = config();
    config.put("bootstrap.servers", "127.0.0.1:9092");
    config.put("zookeeper.connect", "127.0.0.1:2181");




    // Create the consumer
    KafkaReadStream<String, JsonObject> consumer = KafkaReadStream.create(vertx, config.getMap(), String.class, JsonObject.class);


    // Aggregates metrics in the dashboard
    consumer.handler(record -> {
      JsonObject obj = record.value();
      dashboard.mergeIn(obj);
    });

    // Subscribe to Kafka
    consumer.subscribe(Collections.singleton("the_topic"));
  }
}
