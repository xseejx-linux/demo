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
import oshi.hardware.HWDiskStore;

@AutoService(Collector.class)
@CollectorMetadata(
    name = "hardware.disk",
    description = "Disk / storage stats (HDD, SSD, NVMe, USB)",
    tags = {"hardware", "storage"}
)
public class CollectorDisk implements Collector {

    private static final Logger logger = LoggerFactory.getLogger(CollectorDisk.class);

    private boolean includeAll;

    @Override
    public String getName() {
        return "hardware.disk";
    }

    @Override
    @SuppressWarnings("unchecked")
    public CollectorResult collect() {
        try {
            SystemInfo si = new SystemInfo();
            List<HWDiskStore> disks = si.getHardware().getDiskStores();

            JSONObject result = new JSONObject();
            JSONArray diskArray = new JSONArray();

            if (includeAll) {

                for (HWDiskStore disk : disks) {

                    // aggiorna stats realtime
                    disk.updateAttributes();

                    JSONObject d = new JSONObject();

                    d.put("name", disk.getName());
                    d.put("model", disk.getModel());
                    d.put("serial", disk.getSerial());

                    // capacità totale (GB)
                    long sizeBytes = disk.getSize();
                    double sizeGB = sizeBytes / (1024.0 * 1024 * 1024);
                    d.put("sizeGB", String.format("%.2f", sizeGB));

                    // letture / scritture
                    d.put("reads", disk.getReads());
                    d.put("writes", disk.getWrites());

                    // byte letti / scritti
                    double readGB = disk.getReadBytes() / (1024.0 * 1024 * 1024);
                    double writeGB = disk.getWriteBytes() / (1024.0 * 1024 * 1024);

                    d.put("readGB", String.format("%.2f", readGB));
                    d.put("writeGB", String.format("%.2f", writeGB));

                    // tempo attivo
                    d.put("transferTimeMs", disk.getTransferTime());

                    diskArray.add(d);
                }

                result.put("diskCount", disks.size());
                result.put("disks", diskArray);
            }

            return CollectorResult.ok(getName(), result);

        } catch (Exception e) {
            logger.error("Error collecting disk data", e);
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
        CollectorDisk c = new CollectorDisk();
        c.includeAll = true;

        CollectorResult res = c.collect();

        System.out.println("=== DISK CLEAN INFO ===");
        System.out.println(res.getResult().toJSONString());
    }
}