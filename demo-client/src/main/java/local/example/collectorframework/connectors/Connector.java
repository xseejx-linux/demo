package local.example.collectorframework.connectors;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class Connector {

    private static final String SERVER_URL ="http://localhost:8979";

    public final String computerId;

    private final HttpClient client;

    public Connector(String computerId) {
        this.computerId = computerId;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    



    public JSONObject GET(JSONObject jsonBuilder, String endpoint) {
        try {
            String url = SERVER_URL + endpoint;

            if (jsonBuilder != null && !jsonBuilder.isEmpty()) {
                StringBuilder params = new StringBuilder("?");
                for (Object key : jsonBuilder.keySet()) {
                    if (params.length() > 1) {
                        params.append("&");
                    }
                    params.append(key.toString())
                            .append("=")
                            .append(jsonBuilder.get(key).toString());
                }
                url += params.toString();
            }
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() != 200) {
                JSONObject status = new JSONObject();
                status.put("type", "status");
                status.put("status", response.statusCode());
                return status;
            }

            JSONParser parser = new JSONParser();

            return (JSONObject) parser.parse(response.body());

        } catch (IOException | InterruptedException | ParseException e) {
            e.printStackTrace();
            JSONObject status = new JSONObject();
            status.put("type", null);
            return status;
        }
    }

    /**
     * POST JSON to Flask and receives JSON response.
     * @throws InterruptedException 
     * @throws IOException 
     * @throws ParseException 
     */
    public JSONObject POST(JSONObject message, String endpoint) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(SERVER_URL + endpoint))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(message.toString()))
                .build();

        HttpResponse<String> response;
        try {
            JSONParser parser = new JSONParser();
            response = client.send(
                            request,
                            HttpResponse.BodyHandlers.ofString()
                        );
            
            if (response.statusCode() != 200) {
                JSONObject status = new JSONObject();
                status.put("type", "status");
                status.put("status", response.statusCode());
                return status;
            }
            return (JSONObject)parser.parse(response.body());
        } catch (IOException | InterruptedException | ParseException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
            JSONObject status = new JSONObject();
            status.put("type", null);
            return status;
        }

    }
    

}