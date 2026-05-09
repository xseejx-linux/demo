package local.example.collectorframework.collectors;

import java.util.*;
import java.util.stream.Collectors;
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
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

/**
 * Process Collector — stile Task Manager.
 *
 * Strategia CPU %:
 *   OSHI calcola la CPU% di un processo tramite due snapshot distanziati nel tempo.
 *   Prima lettura: snapshot iniziale di tutti i processi (tick CPU cumulativi).
 *   Attesa: SAMPLE_INTERVAL_MS (default 500 ms).
 *   Seconda lettura: nuovo snapshot → delta / intervallo = CPU% per processo.
 *
 *   In questo modo ogni processo riceve una % CPU reale riferita all'intervallo
 *   di campionamento, esattamente come fa il Task Manager di Windows.
 *
 * Parametri configurabili via reflection:
 *   includeAll  → se true mostra tutti i processi (non solo top 10)
 */
@AutoService(Collector.class)
@CollectorMetadata(
    name        = "hardware.process",
    description = "Lista processi attivi ordinati per CPU% — stile Task Manager (top 10 per default)",
    tags        = {"hardware", "realtime"}
)
public class CollectorProcess implements Collector {
    private static final Logger logger = LoggerFactory.getLogger(CollectorProcess.class);

    // ── Parametro configurabile via reflection ──────────────────────────────
    /** Se true restituisce tutti i processi; se false solo i top TOP_N per CPU%. */
    private boolean includeAll;

    // ── Costanti ─────────────────────────────────────────────────────────────
    private static final int    TOP_N               = 10;
    private static final long   SAMPLE_INTERVAL_MS  = 500L;

    /** Processi di sistema da escludere dall'output (case-insensitive). */
    private static final Set<String> BLACKLIST = new HashSet<>(Arrays.asList(
        "idle", "system idle process", "system", "registry",
        "memory compression", "secure system"
    ));

    @Override
    public String getName() { return "hardware.process"; }

    @Override
    @SuppressWarnings("unchecked")
    public CollectorResult collect() {
        try {
            SystemInfo      si  = new SystemInfo();
            OperatingSystem os  = si.getOperatingSystem();
            int             logicalCores = si.getHardware().getProcessor().getLogicalProcessorCount();

            // ── Snapshot 1 ───────────────────────────────────────────────────
            List<OSProcess> snap1 = os.getProcesses();

            // Costruisce una mappa PID → snapshot precedente per il delta CPU
            Map<Integer, OSProcess> prevMap = new HashMap<>();
            for (OSProcess p : snap1) {
                prevMap.put(p.getProcessID(), p);
            }

            // ── Attesa campionamento ─────────────────────────────────────────
            Thread.sleep(SAMPLE_INTERVAL_MS);

            // ── Snapshot 2 ───────────────────────────────────────────────────
            List<OSProcess> snap2 = os.getProcesses();

            // ── Calcolo CPU% e costruzione lista ─────────────────────────────
            List<ProcessEntry> entries = new ArrayList<>();

            for (OSProcess curr : snap2) {
                String name = curr.getName();
                if (name == null || name.isBlank()) continue;
                if (BLACKLIST.contains(name.toLowerCase().trim())) continue;

                OSProcess prev = prevMap.get(curr.getProcessID());

                double cpuPct = 0.0;
                if (prev != null) {
                    // Delta CPU tra i due snapshot, normalizzato sui core logici
                    long deltaProc = (curr.getUserTime()   + curr.getKernelTime())
                                   - (prev.getUserTime()   + prev.getKernelTime());
                    long deltaTime = curr.getUpTime() - prev.getUpTime();
                    if (deltaTime > 0) {
                        cpuPct = (double) deltaProc / deltaTime / logicalCores * 100.0;
                        cpuPct = Math.max(0.0, Math.min(cpuPct, 100.0));
                    }
                }

                entries.add(new ProcessEntry(curr, cpuPct));
            }

            // ── Ordinamento per CPU% decrescente ─────────────────────────────
            entries.sort((a, b) -> Double.compare(b.cpuPct, a.cpuPct));

            // ── Applica limite TOP_N se includeAll = false ───────────────────
            List<ProcessEntry> output = includeAll
                ? entries
                : entries.stream().limit(TOP_N).collect(Collectors.toList());

            // ── Costruzione JSON ─────────────────────────────────────────────
            JSONArray processArray = new JSONArray();

            for (ProcessEntry entry : output) {
                OSProcess p = entry.process;

                JSONObject obj = new JSONObject();
                obj.put("pid",      p.getProcessID());
                obj.put("name",     p.getName());
                obj.put("cpuPct",   String.format("%.2f", entry.cpuPct));
                obj.put("ramMB",    p.getResidentSetSize() / (1024 * 1024));
                obj.put("uptimeSec", p.getUpTime() / 1000L);
                obj.put("path",     p.getPath() != null && !p.getPath().isBlank()
                                        ? p.getPath()
                                        : "N/A");

                processArray.add(obj);
            }

            JSONObject result = new JSONObject();
            result.put("processCount", output.size());
            result.put("processes",    processArray);

            return CollectorResult.ok(getName(), result);

        } catch (Exception e) {
            logger.error("Error occurred while collecting process information", e);
            JSONObject result = new JSONObject();
            result.put("error", e.getMessage());
            return CollectorResult.failure(getName(), result);
        }
    }

    // ── Inner class contenitore ───────────────────────────────────────────────
    private static class ProcessEntry {
        final OSProcess process;
        final double    cpuPct;
        ProcessEntry(OSProcess process, double cpuPct) {
            this.process = process;
            this.cpuPct  = cpuPct;
        }
    }

    @Override
    public Map<String, Class<?>> getAcceptedParameters() {
        return Map.of("includeAll", Boolean.class);
    }

    public static void main(String[] args) throws InterruptedException {
        CollectorProcess collector = new CollectorProcess();
        collector.includeAll = false; // top 10

        System.out.println("=== Process Collector Test ===");
        System.out.println("Campionamento CPU in corso (500ms)...");

        CollectorResult result = collector.collect();
        System.out.println(result.getResult().toJSONString());
    }
}