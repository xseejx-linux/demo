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
import oshi.hardware.CentralProcessor;
import oshi.software.os.OperatingSystem;

@AutoService(Collector.class)
@CollectorMetadata(
    name = "hardware.cpu",
    description = "Reliable CPU stats",
    tags = {"hardware", "realtime"}
)
public class CollectorCPU implements Collector {

    private static final Logger logger = LoggerFactory.getLogger(CollectorCPU.class);

    private boolean includeAll;

    @Override
    public String getName() {
        return "hardware.cpu";
    }

    @Override
    @SuppressWarnings("unchecked")
    public CollectorResult collect() {
        try {
            SystemInfo si = new SystemInfo();
            CentralProcessor cpu = si.getHardware().getProcessor();
            OperatingSystem os = si.getOperatingSystem();

            JSONObject result = new JSONObject();

            if (includeAll) {

                long[] prevTicks = cpu.getSystemCpuLoadTicks();
                Thread.sleep(1000);
                double cpuLoad = cpu.getSystemCpuLoadBetweenTicks(prevTicks) * 100;
                result.put("cpuUsagePercent", String.format("%.2f", cpuLoad));

                long maxFreq = cpu.getMaxFreq();
                double freqGHz = (maxFreq / 1_000_000_000.0) * (cpuLoad / 100.0);
                result.put("estimatedFrequencyGHz", String.format("%.2f", 3.5 + freqGHz));

                result.put("baseFrequencyGHz", String.format("%.2f", maxFreq / 1_000_000_000.0));

                result.put("physicalCores", cpu.getPhysicalProcessorCount());
                result.put("logicalCores", cpu.getLogicalProcessorCount());

                result.put("processCount", os.getProcessCount());
                result.put("threadCount", os.getThreadCount());

                result.put("uptimeSeconds", os.getSystemUptime());

                result.put("cpuName", cpu.getProcessorIdentifier().getName());
                result.put("architecture", System.getProperty("os.arch"));
            }

            return CollectorResult.ok(getName(), result);

        } catch (Exception e) {
            logger.error("Error collecting CPU data", e);
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

    public static void main(String[] args) throws Exception {
        CollectorCPU c = new CollectorCPU();
        c.includeAll = true;

        CollectorResult res = c.collect();

        System.out.println("=== CPU CLEAN INFO ===");
        System.out.println(res.getResult().toJSONString());
    }
}