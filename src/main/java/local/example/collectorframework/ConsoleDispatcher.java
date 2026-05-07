package local.example.collectorframework;

// IMPORTS API
import io.github.xseejx.collectorframework.api.CollectorResult;
import io.github.xseejx.collectorframework.api.DispatcherMetadata;
import io.github.xseejx.collectorframework.api.ResultDispatcher;
// IMPORTS JSON
import org.json.simple.JSONObject;

import com.google.auto.service.AutoService;

/**
 * Class: ConsoleDispatcher
 * A simple implementation of ResultDispatcher that prints the result to the console.
 * Used for Testing (in production is obsolete)
 */
@AutoService(ResultDispatcher.class)
@DispatcherMetadata(name = "console", description = "Prints task results to the console")
//TODO: move inside package dispatcher
public class ConsoleDispatcher implements ResultDispatcher {

    @SuppressWarnings("unchecked")
    @Override
    public void dispatch(String taskId, String groupName, CollectorResult result) {
        JSONObject payload = new JSONObject();
        payload.put("taskId", taskId);
        payload.put("group", groupName);
        payload.put("collectorName", result.getResult().get("collectorName"));
        payload.put("success", result.getResult().get("success"));
        payload.put("errorMessage", result.getResult().get("errorMessage"));
        payload.put("timestamp", result.getResult().get("timestamp"));
        payload.put("data", result.getResult().get("data"));

        System.out.println("[Dispatcher] Task result: " + payload.toJSONString());
    }
}
