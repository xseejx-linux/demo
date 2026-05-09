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
import org.json.simple.JSONObject;
import org.json.simple.JSONArray;

import oshi.SystemInfo;
import oshi.hardware.GraphicsCard;

/**
 * GPU Collector — dati statici via OSHI (cross-platform, zero dipendenze aggiuntive).
 *
 * Dati realtime (temperatura, utilizzo, power draw) NON sono inclusi perché
 * richiedono tool vendor-specifici non disponibili su tutti i sistemi:
 *   - NVIDIA → nvidia-smi  (solo GPU NVIDIA con driver installato)
 *   - AMD    → amd-smi / rocm-smi (solo Linux con stack ROCm, non standard su desktop)
 *   - Intel  → nessuno strumento cross-platform affidabile
 *
 * Tutto ciò che questo collector restituisce è reale e disponibile su ogni OS.
 */
@AutoService(Collector.class)
@CollectorMetadata(
    name        = "hardware.gpu",
    description = "GPU name, total VRAM, vendor, driver version and device ID (cross-platform via OSHI)",
    tags        = {"hardware"}
)
public class CollectorGPU implements Collector {
    private static final Logger logger = LoggerFactory.getLogger(CollectorGPU.class);

    // With reflective modify those values on core
    private boolean includeVram;
    private boolean includeVendor;
    private boolean includeDriverVersion;
    private boolean includeDeviceId;

    @Override
    public String getName() { return "hardware.gpu"; }

    @Override
    @SuppressWarnings("unchecked")
    public CollectorResult collect() {
        try {
            SystemInfo si = new SystemInfo();
            List<GraphicsCard> gpus = si.getHardware().getGraphicsCards();

            JSONObject result = new JSONObject();

            if (gpus == null || gpus.isEmpty()) {
                result.put("gpus", new JSONArray());
                result.put("count", 0);
                return CollectorResult.ok(getName(), result);
            }

            JSONArray gpuArray = new JSONArray();

            for (int i = 0; i < gpus.size(); i++) {
                GraphicsCard gpu = gpus.get(i);
                JSONObject gpuEntry = new JSONObject();

                gpuEntry.put("index", i);
                gpuEntry.put("name", gpu.getName() != null ? gpu.getName().trim() : "Unknown");

                if (includeVram) {
                    long vramBytes = gpu.getVRam();
                    gpuEntry.put("vramTotalMB", vramBytes > 0 ? vramBytes / (1024 * 1024) : "OFF");
                } else {
                    gpuEntry.put("vramTotalMB", "OFF");
                }

                if (includeVendor) {
                    String vendor = gpu.getVendor();
                    gpuEntry.put("vendor", (vendor != null && !vendor.isBlank()) ? vendor.trim() : "Unknown");
                } else {
                    gpuEntry.put("vendor", "OFF");
                }

                if (includeDriverVersion) {
                    String driver = gpu.getVersionInfo();
                    gpuEntry.put("driverVersion", (driver != null && !driver.isBlank()) ? driver.trim() : "Unknown");
                } else {
                    gpuEntry.put("driverVersion", "OFF");
                }

                if (includeDeviceId) {
                    String deviceId = gpu.getDeviceId();
                    gpuEntry.put("deviceId", (deviceId != null && !deviceId.isBlank()) ? deviceId.trim() : "Unknown");
                } else {
                    gpuEntry.put("deviceId", "OFF");
                }

                gpuArray.add(gpuEntry);
            }

            result.put("gpus", gpuArray);
            result.put("count", gpus.size());

            return CollectorResult.ok(getName(), result);

        } catch (Exception e) {
            logger.error("Error occurred while collecting GPU information", e);
            JSONObject result = new JSONObject();
            result.put("error", e.getMessage());
            return CollectorResult.failure(getName(), result);
        }
    }

    @Override
    public Map<String, Class<?>> getAcceptedParameters() {
        return Map.of(
            "includeVram",          Boolean.class,
            "includeVendor",        Boolean.class,
            "includeDriverVersion", Boolean.class,
            "includeDeviceId",      Boolean.class
        );
    }

    public static void main(String[] args) {
        CollectorGPU collector = new CollectorGPU();

        collector.includeVram          = true;
        collector.includeVendor        = true;
        collector.includeDriverVersion = true;
        collector.includeDeviceId      = true;

        CollectorResult result = collector.collect();

        System.out.println("=== GPU Collector Test ===");
        System.out.println(result.getResult().toJSONString());
    }
}