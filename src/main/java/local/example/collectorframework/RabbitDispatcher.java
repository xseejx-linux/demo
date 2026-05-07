package local.example.collectorframework;

import com.google.auto.service.AutoService;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import io.github.xseejx.collectorframework.api.CollectorResult;
import io.github.xseejx.collectorframework.api.DispatcherMetadata;
import io.github.xseejx.collectorframework.api.ResultDispatcher;
import org.json.simple.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;

@AutoService(ResultDispatcher.class)
@DispatcherMetadata(name = "rabbitmq", description = "RabbitMQ dispatcher")
public class RabbitDispatcher implements ResultDispatcher, AutoCloseable {

    private static final String EXCHANGE_NAME = "collector.results";
    private static final String EXCHANGE_TYPE = "direct";
    ConnectionFactory factory = new ConnectionFactory();

    private final Connection connection;
    private final Channel channel;

    public RabbitDispatcher() {
        try {
            factory.setHost("localhost");
            factory.setPort(5672);
            factory.setUsername("guest");
            factory.setPassword("guest");
            

            this.connection = factory.newConnection();
            this.channel = connection.createChannel();

            // Declare once, not on every dispatch
            this.channel.exchangeDeclare(EXCHANGE_NAME, EXCHANGE_TYPE, true);
        } catch (IOException | TimeoutException e) {
            throw new RuntimeException("Failed to connect to RabbitMQ", e);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public void dispatch(String taskId, String groupName, CollectorResult result) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("taskId", taskId);
            payload.put("group", groupName);
            payload.put("collectorName", result.getResult().get("collectorName"));
            payload.put("success", result.getResult().get("success"));
            payload.put("errorMessage", result.getResult().get("errorMessage"));
            payload.put("timestamp", result.getResult().get("timestamp"));
            payload.put("data", result.getResult().get("data"));

            String json = payload.toJSONString();
            byte[] body = json.getBytes(StandardCharsets.UTF_8);

            // routing key can be the group name, collector name, or anything you want
            channel.basicPublish(
                    EXCHANGE_NAME,
                    groupName,
                    null,
                    body
            );

            System.out.println("[RabbitDispatcher] Sent to RabbitMQ: " + json);
        } catch (IOException e) {
            throw new RuntimeException("Failed to publish result to RabbitMQ", e);
        }
    }

    @Override
    public void close() {
        try {
            if (channel != null && channel.isOpen()) channel.close();
            if (connection != null && connection.isOpen()) connection.close();
        } catch (IOException | TimeoutException e) {
            throw new RuntimeException("Failed to close RabbitMQ resources", e);
        }
    }
}