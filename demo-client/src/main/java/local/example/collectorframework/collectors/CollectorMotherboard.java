package local.example.collectorframework.collectors;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.auto.service.AutoService;

import io.github.xseejx.collectorframework.api.Collector;
import io.github.xseejx.collectorframework.api.CollectorMetadata;
import io.github.xseejx.collectorframework.api.CollectorResult;
import io.github.xseejx.collectorframework.api.CollectorMetadata.ParameterType;

import org.json.simple.JSONObject;

import oshi.SystemInfo;
import oshi.hardware.Baseboard;
import oshi.hardware.ComputerSystem;
import oshi.hardware.Firmware;

@AutoService(Collector.class)
@CollectorMetadata(
    name = "hardware.motherboard",
    description = "Motherboard and BIOS information",
    tags = {"hardware", "static"}
)
public class CollectorMotherboard implements Collector {

    private static final Logger logger = LoggerFactory.getLogger(CollectorMotherboard.class);

    private boolean includeAll;

    @Override
    public String getName() {
        return "hardware.motherboard";
    }

    @Override
    @SuppressWarnings("unchecked")
    public CollectorResult collect() {
        try {
            SystemInfo si = new SystemInfo();
            ComputerSystem cs = si.getHardware().getComputerSystem();
            Baseboard bb = cs.getBaseboard();
            Firmware fw = cs.getFirmware();

            JSONObject result = new JSONObject();

            if (includeAll) {

                JSONObject baseboard = new JSONObject();
                baseboard.put("manufacturer", safe(bb.getManufacturer()));
                baseboard.put("model", safe(bb.getModel()));
                baseboard.put("version", safe(bb.getVersion()));
                baseboard.put("serialNumber", safe(bb.getSerialNumber()));
                result.put("baseboard", baseboard);

                JSONObject system = new JSONObject();
                system.put("manufacturer", safe(cs.getManufacturer()));
                system.put("model", safe(cs.getModel()));
                system.put("serialNumber", safe(cs.getSerialNumber()));
                result.put("system", system);

                JSONObject bios = new JSONObject();
                bios.put("manufacturer", safe(fw.getManufacturer()));
                bios.put("name", safe(fw.getName()));
                bios.put("version", safe(fw.getVersion()));
                bios.put("description", safe(fw.getDescription()));
                bios.put("releaseDate", safe(fw.getReleaseDate()));
                result.put("bios", bios);
            }

            return CollectorResult.ok(getName(), result);

        } catch (Exception e) {
            logger.error("Error collecting motherboard info", e);
            JSONObject result = new JSONObject();
            result.put("error", e.getMessage());
            return CollectorResult.failure(getName(), result);
        }
    }

    private String safe(String s) {
        return (s == null || s.isBlank()) ? "N/A" : s;
    }

    @Override
    public Map<String, Class<?>> getAcceptedParameters() {
        return Map.of(
            "includeAll", Boolean.class
        );
    }

    public static void main(String[] args) {
        CollectorMotherboard c = new CollectorMotherboard();
        c.includeAll = true;

        CollectorResult res = c.collect();

        System.out.println("=== MOTHERBOARD INFO ===");
        System.out.println(res.getResult().toJSONString());
    }
}