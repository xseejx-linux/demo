package local.example.collectorframework.collectors;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.auto.service.AutoService;

import io.github.xseejx.collectorframework.api.Collector;
import io.github.xseejx.collectorframework.api.CollectorMetadata;
import io.github.xseejx.collectorframework.api.CollectorResult;
import io.github.xseejx.collectorframework.api.CollectorMetadata.ParameterType;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import oshi.SystemInfo;
import oshi.software.os.FileSystem;
import oshi.software.os.OSFileStore;

@AutoService(Collector.class)
@CollectorMetadata(
    name = "system.filesystem",
    description = "Filesystem usage (space, mounts, usage %)",
    tags = {"system", "storage"}
)
public class CollectorFilesystem implements Collector {

    private static final Logger logger = LoggerFactory.getLogger(CollectorFilesystem.class);

    private boolean includeAll;

    @Override
    public String getName() {
        return "system.filesystem";
    }

    @Override
    @SuppressWarnings("unchecked")
    public CollectorResult collect() {
        try {
            SystemInfo si = new SystemInfo();
            FileSystem fs = si.getOperatingSystem().getFileSystem();
            List<OSFileStore> stores = fs.getFileStores();

            JSONObject result = new JSONObject();
            JSONArray fsArray = new JSONArray();

            if (includeAll) {

                for (OSFileStore store : stores) {

                    JSONObject s = new JSONObject();

                    long total = store.getTotalSpace();
                    long usable = store.getUsableSpace();
                    long used = total - usable;

                    double totalGB = total / (1024.0 * 1024 * 1024);
                    double usableGB = usable / (1024.0 * 1024 * 1024);
                    double usedGB = used / (1024.0 * 1024 * 1024);

                    double usagePercent = total > 0
                        ? (used * 100.0 / total)
                        : 0;

                    s.put("name", store.getName());
                    s.put("mount", store.getMount());
                    s.put("type", store.getType());
                    s.put("volume", store.getVolume());

                    s.put("totalGB", String.format("%.2f", totalGB));
                    s.put("usableGB", String.format("%.2f", usableGB));
                    s.put("usedGB", String.format("%.2f", usedGB));
                    s.put("usagePercent", String.format("%.2f", usagePercent));

                    fsArray.add(s);
                }

                result.put("filesystemCount", stores.size());
                result.put("filesystems", fsArray);
            }

            return CollectorResult.ok(getName(), result);

        } catch (Exception e) {
            logger.error("Error collecting filesystem data", e);
            JSONObject result = new JSONObject();
            result.put("error", e.getMessage());
            return CollectorResult.failure(getName(), result);
        }
    }

    @Override
    public Map<String, Class<?>> getAcceptedParameters() {
        return Map.of(
            "includeAll", Boolean.class
        );
    }

    public static void main(String[] args) {
        CollectorFilesystem c = new CollectorFilesystem();
        c.includeAll = true;

        CollectorResult res = c.collect();

        System.out.println("=== FILESYSTEM CLEAN INFO ===");
        System.out.println(res.getResult().toJSONString());
    }
}
