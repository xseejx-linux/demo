package local.example.collectorframework;

import java.util.List;
import java.util.Map;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import io.github.xseejx.collectorframework.engine.ServiceManager;

/**
 * Utility class that collects metadata from all available {@code Collector}
 * implementations and returns it in a structured JSON format, including
 * internally discovered parameters when annotations are omitted.
 */
public class CollectorMetadataJsonExporter {

    /**
     * Gathers metadata for every available collector, including any
     * <strong>unlisted parameters</strong> that are declared via
     * {@link Collector#getAcceptedParameters()} but are absent from the
     * {@code CollectorMetadata} annotation.
     * <p>
     * The returned JSON object has the following structure:
     * <pre>
     * {
     *   "type": "metadata_result",
     *   "messagge": [
     *     {
     *       "collector": "fully.qualified.CollectorName",
     *       "metadata": [
     *         "name: MyCollector",
     *         "description: A sample collector",
     *         ...
     *       ],
     *       "unlistedParameters": [
     *         {
     *           "key": "recursive",
     *           "type": "BOOLEAN",
     *           "defaultValue": "",
     *           "required": false
     *         }
     *       ]
     *     }
     *   ]
     * }
     * </pre>
     * The {@code unlistedParameters} array is present only when the collector
     * provides additional parameters beyond those declared in its metadata
     * annotation.
     *
     * @return a {@link JSONObject} representing all collectors, their metadata,
     *         and any internally discovered parameters
     */
    public static JSONObject gatherAllMetadataAsJson() {
        ServiceManager service = new ServiceManager();
        List<String> collectors = service.listAvailable();

        JSONObject result = new JSONObject();
        result.put("type", "metadata_result");

        JSONArray messageArray = new JSONArray();

        for (String collectorName : collectors) {
            JSONObject collectorData = new JSONObject();
            collectorData.put("collector", collectorName);

            // standard metadata strings
            List<String> metadataLines = service.getMetadata(collectorName);
            JSONArray metadataArray = new JSONArray();
            metadataArray.addAll(metadataLines);
            collectorData.put("metadata", metadataArray);

            // internal parameters that are not listed in the annotation
            List<Map<String, Object>> unlistedParams = service.getUnlistedParameters(collectorName);
            if (unlistedParams != null && !unlistedParams.isEmpty()) {
                JSONArray paramsArray = new JSONArray();
                for (Map<String, Object> paramMap : unlistedParams) {
                    JSONObject paramObj = new JSONObject();
                    paramObj.putAll(paramMap);
                    paramsArray.add(paramObj);
                }
                collectorData.put("unlistedParameters", paramsArray);
            }

            messageArray.add(collectorData);
        }

        result.put("messagge", messageArray);
        return result;
    }
}