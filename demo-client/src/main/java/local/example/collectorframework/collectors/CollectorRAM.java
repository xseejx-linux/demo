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
import org.json.simple.JSONArray;
 
import oshi.SystemInfo;
import oshi.hardware.GlobalMemory;
import oshi.hardware.PhysicalMemory;
 
import java.util.List;
 
@AutoService(Collector.class)
@CollectorMetadata(
    name        = "hardware.ram",
    description = "RAM usage, total, available, swap and physical memory bank details",
    tags        = {"hardware", "realtime"}
)
 
public class CollectorRAM implements Collector {
    private static final Logger logger = LoggerFactory.getLogger(CollectorRAM.class);
 
    // With reflective modify those values on core
    private boolean includeUsagePercent;
    private boolean includeSwap;
    private boolean includePhysicalBanks;
    private boolean includePageSize;
 
    @Override
    public String getName() { return "hardware.ram"; }
 
    @Override
    @SuppressWarnings("unchecked")
    public CollectorResult collect() {
        try {
            SystemInfo si = new SystemInfo();
            GlobalMemory memory = si.getHardware().getMemory();
 
            JSONObject result = new JSONObject();
 
            // Always included: total, available, used (in MB)
            long totalBytes     = memory.getTotal();
            long availableBytes = memory.getAvailable();
            long usedBytes      = totalBytes - availableBytes;
 
            result.put("totalMB",     totalBytes     / (1024 * 1024));
            result.put("availableMB", availableBytes / (1024 * 1024));
            result.put("usedMB",      usedBytes      / (1024 * 1024));
 
            // Usage percentage
            if (includeUsagePercent) {
                double usagePercent = totalBytes > 0
                    ? (double) usedBytes / totalBytes * 100.0
                    : 0.0;
                result.put("usagePercent", String.format("%.1f", usagePercent));
            } else {
                result.put("usagePercent", "OFF");
            }
 
            // Swap memory
            if (includeSwap) {
                long swapTotal = memory.getVirtualMemory().getSwapTotal();
                long swapUsed  = memory.getVirtualMemory().getSwapUsed();
                JSONObject swap = new JSONObject();
                if (swapTotal > 0) {
                    swap.put("totalMB", swapTotal / (1024 * 1024));
                    swap.put("usedMB",  swapUsed  / (1024 * 1024));
                    swap.put("freeMB",  (swapTotal - swapUsed) / (1024 * 1024));
                } else {
                    swap.put("totalMB", "N/A");
                    swap.put("usedMB",  "N/A");
                    swap.put("freeMB",  "N/A");
                }
                result.put("swap", swap);
            } else {
                result.put("swap", "OFF");
            }
 
            // Physical memory banks (slots/sticks details)
            if (includePhysicalBanks) {
                List<PhysicalMemory> banks = memory.getPhysicalMemory();
                JSONArray bankArray = new JSONArray();
                for (PhysicalMemory bank : banks) {
                    JSONObject bankEntry = new JSONObject();
                    bankEntry.put("bankLabel",   bank.getBankLabel()   != null ? bank.getBankLabel()   : "N/A");
                    bankEntry.put("manufacturer", bank.getManufacturer() != null ? bank.getManufacturer() : "N/A");
                    bankEntry.put("memoryType",  bank.getMemoryType()  != null ? bank.getMemoryType()  : "N/A");
                    bankEntry.put("capacityMB",  bank.getCapacity() / (1024 * 1024));
                    long clockSpeed = bank.getClockSpeed();
                    bankEntry.put("clockSpeedMHz", clockSpeed > 0 ? clockSpeed / 1_000_000 : "N/A");
                    bankArray.add(bankEntry);
                }
                result.put("physicalBanks", bankArray);
                result.put("bankCount", banks.size());
            } else {
                result.put("physicalBanks", "OFF");
            }
 
            // Memory page size
            if (includePageSize) {
                long pageSize = memory.getPageSize();
                result.put("pageSizeBytes", pageSize > 0 ? pageSize : "N/A");
            } else {
                result.put("pageSizeBytes", "OFF");
            }
 
            return CollectorResult.ok(getName(), result);
 
        } catch (Exception e) {
            logger.error("Error occurred while collecting RAM information", e);
            JSONObject result = new JSONObject();
            result.put("error", e.getMessage());
            return CollectorResult.failure(getName(), result);
        }
    }
 
    @Override
    public Map<String, Class<?>> getAcceptedParameters() {
        return Map.of(
            "includeUsagePercent", Boolean.class,
            "includeSwap",         Boolean.class,
            "includePhysicalBanks",Boolean.class,
            "includePageSize",     Boolean.class
        );
    }
 
    public static void main(String[] args) {
        CollectorRAM collector = new CollectorRAM();
 
        // Abilita tutte le funzionalità per il test
        collector.includeUsagePercent  = true;
        collector.includeSwap          = true;
        collector.includePhysicalBanks = true;
        collector.includePageSize      = true;
 
        CollectorResult result = collector.collect();
 
        System.out.println("=== RAM Collector Test ===");
        System.out.println(result.getResult().toJSONString());
    }
}