package local.example.collectorframework.collectors;

import java.util.*;
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
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.NetworkIF;
import oshi.hardware.Sensors;
import oshi.software.os.FileSystem;
import oshi.software.os.OSFileStore;

/**
 * System Health Collector v2.
 *
 * Aggrega CPU, RAM, storage, rete e temperatura in un singolo healthScore 0–100.
 *
 * ── Pesi dei componenti (somma = 100%) ──────────────────────────────────────
 *   CPU          25%   utilizzo istantaneo (campione 500ms)
 *   RAM          25%   percentuale heap fisico usato
 *   Storage      20%   disco più critico tra quelli montati
 *   Network      10%   errori/drop su interfacce attive (campione 500ms)
 *   Temperature  20%   temperatura CPU (se disponibile; ignorata se N/A)
 *
 * NOTA temperatura: su Windows richiede LibreHardwareMonitor in esecuzione
 * o privilegi Administrator. Se il valore non è disponibile (0 o NaN), il
 * peso della temperatura viene ridistribuito sugli altri componenti in modo
 * proporzionale, così lo score rimane significativo anche senza sensori.
 *
 * ── Sub-score 0–100 per componente ─────────────────────────────────────────
 *   Zona normale  (0  → warning)  : calo graduale  100 → 70
 *   Zona warning  (warning → crit): calo accelerato  70 → 20
 *   Zona critica  (crit → 100%)   : calo drastico    20 → 0
 *
 * ── Soglie stato globale ────────────────────────────────────────────────────
 *   healthScore >= 75  → HEALTHY
 *   healthScore >= 50  → WARNING
 *   healthScore <  50  → CRITICAL
 *
 * Parametri via reflection:
 *   includeAll  → aggiunge breakdown per componente nel JSON
 *   strictMode  → soglie più stringenti (healthy ≥ 80, warning ≥ 60)
 */
@AutoService(Collector.class)
@CollectorMetadata(
    name        = "hardware.health",
    description = "Score di salute globale 0–100: CPU, RAM, storage, rete e temperatura",
    tags        = {"hardware", "health", "realtime"}
)
public class CollectorHealth implements Collector {
    private static final Logger logger = LoggerFactory.getLogger(CollectorHealth.class);

    // With reflective modify those values on core
    boolean includeAll;
    boolean strictMode;

    // ── Pesi base (devono sommare a 1.0) ─────────────────────────────────────
    private static final double W_CPU   = 0.25;
    private static final double W_RAM   = 0.25;
    private static final double W_DISK  = 0.20;
    private static final double W_NET   = 0.10;
    private static final double W_TEMP  = 0.20;

    // ── Soglie CPU ────────────────────────────────────────────────────────────
    private static final double CPU_WARN = 70.0;
    private static final double CPU_CRIT = 90.0;

    // ── Soglie RAM ────────────────────────────────────────────────────────────
    private static final double RAM_WARN = 75.0;
    private static final double RAM_CRIT = 90.0;

    // ── Soglie Storage ────────────────────────────────────────────────────────
    private static final double DISK_WARN = 80.0;
    private static final double DISK_CRIT = 95.0;

    // ── Soglie Temperatura CPU (°C) ───────────────────────────────────────────
    private static final double TEMP_WARN = 75.0;
    private static final double TEMP_CRIT = 90.0;
    private static final double TEMP_MAX  = 110.0;  // normalizzazione score

    // ── Soglie errori rete (delta nell'intervallo di campionamento) ───────────
    private static final long NET_ERR_WARN = 10L;
    private static final long NET_ERR_CRIT = 50L;

    // ── Soglie stato globale ──────────────────────────────────────────────────
    private static final double SCORE_HEALTHY       = 75.0;
    private static final double SCORE_WARNING       = 50.0;
    private static final double SCORE_HEALTHY_STRICT = 80.0;
    private static final double SCORE_WARNING_STRICT = 60.0;

    // ── Campionamento ─────────────────────────────────────────────────────────
    private static final long CPU_SAMPLE_MS = 500L;
    private static final long NET_SAMPLE_MS = 500L;

    // ── Filesystem pseudo-virtuali da ignorare ────────────────────────────────
    private static final Set<String> PSEUDO_FS = new HashSet<>(Arrays.asList(
        "tmpfs","devtmpfs","devfs","sysfs","proc","cgroup","cgroup2",
        "pstore","securityfs","debugfs","hugetlbfs","mqueue","fusectl",
        "efivarfs","bpf","tracefs","configfs","ramfs","overlay",
        "squashfs","autofs","rpc_pipefs","nfsd","sunrpc"
    ));

    @Override
    public String getName() { return "hardware.health"; }

    @Override
    @SuppressWarnings("unchecked")
    public CollectorResult collect() {
        try {
            SystemInfo si = new SystemInfo();

            JSONArray  alerts    = new JSONArray();
            JSONObject breakdown = new JSONObject();

            // ════════════════════════════════════════════════════════════════
            // 1. CPU
            // ════════════════════════════════════════════════════════════════
            CentralProcessor cpu    = si.getHardware().getProcessor();
            double cpuPct           = sampleCpu(cpu);
            double cpuScore         = score(cpuPct, CPU_WARN, CPU_CRIT);

            if (cpuPct >= CPU_CRIT) {
                alerts.add(String.format(
                    "[CPU] Utilizzo critico: %.1f%% — possibile collo di bottiglia o processo runaway",
                    cpuPct));
            } else if (cpuPct >= CPU_WARN) {
                alerts.add(String.format(
                    "[CPU] Utilizzo elevato: %.1f%%", cpuPct));
            }

            if (includeAll) {
                JSONObject b = new JSONObject();
                b.put("usedPct", fmt1(cpuPct));
                b.put("score",   Math.round(cpuScore));
                b.put("status",  status(cpuPct, CPU_WARN, CPU_CRIT));
                b.put("weight",  pct(W_CPU));
                breakdown.put("cpu", b);
            }

            // ════════════════════════════════════════════════════════════════
            // 2. RAM
            // ════════════════════════════════════════════════════════════════
            GlobalMemory mem   = si.getHardware().getMemory();
            long totalB        = mem.getTotal();
            long availB        = mem.getAvailable();
            long usedB         = totalB - availB;
            double ramPct      = totalB > 0 ? (double) usedB / totalB * 100.0 : 0.0;
            double ramScore    = score(ramPct, RAM_WARN, RAM_CRIT);

            if (ramPct >= RAM_CRIT) {
                alerts.add(String.format(
                    "[RAM] Utilizzo critico: %.1f%% (%d MB liberi) — rischio OOM",
                    ramPct, availB / (1024 * 1024)));
            } else if (ramPct >= RAM_WARN) {
                alerts.add(String.format(
                    "[RAM] Utilizzo elevato: %.1f%% (%d MB liberi)",
                    ramPct, availB / (1024 * 1024)));
            }

            if (includeAll) {
                JSONObject b = new JSONObject();
                b.put("usedPct",     fmt1(ramPct));
                b.put("usedMB",      usedB / (1024 * 1024));
                b.put("totalMB",     totalB / (1024 * 1024));
                b.put("availableMB", availB / (1024 * 1024));
                b.put("score",       Math.round(ramScore));
                b.put("status",      status(ramPct, RAM_WARN, RAM_CRIT));
                b.put("weight",      pct(W_RAM));
                breakdown.put("ram", b);
            }

            // ════════════════════════════════════════════════════════════════
            // 3. STORAGE — disco più critico
            // ════════════════════════════════════════════════════════════════
            FileSystem fs         = si.getOperatingSystem().getFileSystem();
            List<OSFileStore> stores = fs.getFileStores(true);

            double worstDiskPct   = 0.0;
            String worstMount     = "N/A";
            int    diskCount      = 0;
            JSONArray diskDetails = new JSONArray();

            for (OSFileStore store : stores) {
                String type = store.getType() != null ? store.getType().trim() : "";
                if (PSEUDO_FS.contains(type.toLowerCase(Locale.ROOT))) continue;
                long total = store.getTotalSpace();
                if (total == 0) continue;
                long free = store.getUsableSpace();
                double pct = (double)(total - free) / total * 100.0;
                diskCount++;
                if (pct > worstDiskPct) { worstDiskPct = pct; worstMount = store.getMount(); }
                if (includeAll) {
                    JSONObject d = new JSONObject();
                    d.put("mount",   store.getMount());
                    d.put("usedPct", fmt1(pct));
                    d.put("totalGB", fmt1(total / (1024.0 * 1024 * 1024)));
                    d.put("freeGB",  fmt1(free  / (1024.0 * 1024 * 1024)));
                    diskDetails.add(d);
                }
            }

            double diskScore = score(worstDiskPct, DISK_WARN, DISK_CRIT);

            if (worstDiskPct >= DISK_CRIT) {
                alerts.add(String.format(
                    "[DISK] Disco quasi pieno: \"%s\" al %.1f%% — spazio esaurito imminente",
                    worstMount, worstDiskPct));
            } else if (worstDiskPct >= DISK_WARN) {
                alerts.add(String.format(
                    "[DISK] Utilizzo disco elevato: \"%s\" al %.1f%%",
                    worstMount, worstDiskPct));
            }

            if (includeAll) {
                JSONObject b = new JSONObject();
                b.put("worstDiskPct",  fmt1(worstDiskPct));
                b.put("worstMount",    worstMount);
                b.put("diskCount",     diskCount);
                b.put("score",         Math.round(diskScore));
                b.put("status",        status(worstDiskPct, DISK_WARN, DISK_CRIT));
                b.put("weight",        pct(W_DISK));
                b.put("disks",         diskDetails);
                breakdown.put("storage", b);
            }

            // ════════════════════════════════════════════════════════════════
            // 4. NETWORK — errori e drop
            // ════════════════════════════════════════════════════════════════
            long[] netErrors = sampleNetErrors(si);
            long totalErrors = netErrors[0];
            long totalDrops  = netErrors[1];
            long netIssues   = totalErrors + totalDrops;

            double netScore;
            String netSt;
            if      (netIssues >= NET_ERR_CRIT) { netScore = 20.0;  netSt = "CRITICAL"; }
            else if (netIssues >= NET_ERR_WARN)  { netScore = 60.0;  netSt = "WARNING";  }
            else if (netIssues > 0)              { netScore = 85.0;  netSt = "WARNING";  }
            else                                 { netScore = 100.0; netSt = "HEALTHY";  }

            if (netIssues >= NET_ERR_CRIT) {
                alerts.add(String.format(
                    "[NET] Errori di rete critici: %d errori + %d drop — adattatore o cavo degradato",
                    totalErrors, totalDrops));
            } else if (netIssues >= NET_ERR_WARN) {
                alerts.add(String.format(
                    "[NET] Errori di rete: %d errori + %d drop", totalErrors, totalDrops));
            }

            if (includeAll) {
                JSONObject b = new JSONObject();
                b.put("errors", totalErrors);
                b.put("drops",  totalDrops);
                b.put("score",  Math.round(netScore));
                b.put("status", netSt);
                b.put("weight", pct(W_NET));
                breakdown.put("network", b);
            }

            // ════════════════════════════════════════════════════════════════
            // 5. TEMPERATURA CPU
            // ════════════════════════════════════════════════════════════════
            Sensors sensors = si.getHardware().getSensors();
            double  temp    = sensors.getCpuTemperature();
            boolean tempOk  = !Double.isNaN(temp) && temp > 0.0 && temp < 150.0;

            double tempScore  = 100.0;  // default "perfetto" se non disponibile
            double wTemp      = tempOk ? W_TEMP : 0.0;  // peso azzerato se N/A

            if (tempOk) {
                // Normalizza: 0°C → score 100, TEMP_CRIT → score 20, TEMP_MAX → score 0
                tempScore = score(temp, TEMP_WARN, TEMP_CRIT);

                if (temp >= TEMP_CRIT) {
                    alerts.add(String.format(
                        "[TEMP] Temperatura CPU critica: %.1f°C — rischio throttling o danno hardware",
                        temp));
                } else if (temp >= TEMP_WARN) {
                    alerts.add(String.format(
                        "[TEMP] Temperatura CPU elevata: %.1f°C — verificare il raffreddamento",
                        temp));
                }
            }

            if (includeAll) {
                JSONObject b = new JSONObject();
                b.put("temperatureC", tempOk ? fmt1(temp) : "N/A");
                b.put("available",    tempOk);
                b.put("score",        Math.round(tempScore));
                b.put("status",       tempOk ? status(temp, TEMP_WARN, TEMP_CRIT) : "UNAVAILABLE");
                b.put("weight",       tempOk ? pct(W_TEMP) : "0% (N/A — ridistribuito)");
                if (!tempOk) {
                    b.put("note",
                        "Su Windows avvia LibreHardwareMonitor come servizio per leggere la temperatura");
                }
                breakdown.put("temperature", b);
            }

            // ════════════════════════════════════════════════════════════════
            // HEALTH SCORE — media pesata con redistribuzione se temp N/A
            // ════════════════════════════════════════════════════════════════

            // Se la temperatura non è disponibile il suo peso (W_TEMP = 20%)
            // viene ridistribuito sugli altri componenti in proporzione ai loro pesi.
            double totalWeight = W_CPU + W_RAM + W_DISK + W_NET + wTemp;

            double healthScore =
                (cpuScore  * W_CPU  +
                 ramScore  * W_RAM  +
                 diskScore * W_DISK +
                 netScore  * W_NET  +
                 tempScore * wTemp) / totalWeight;

            healthScore = Math.max(0.0, Math.min(100.0, healthScore));

            double thHealthy = strictMode ? SCORE_HEALTHY_STRICT : SCORE_HEALTHY;
            double thWarning = strictMode ? SCORE_WARNING_STRICT  : SCORE_WARNING;

            String globalStatus;
            if      (healthScore >= thHealthy) globalStatus = "HEALTHY";
            else if (healthScore >= thWarning)  globalStatus = "WARNING";
            else                                globalStatus = "CRITICAL";

            // ── Output ────────────────────────────────────────────────────────
            JSONObject result = new JSONObject();
            result.put("healthScore",   Math.round(healthScore));
            result.put("status",        globalStatus);
            result.put("alertCount",    alerts.size());
            result.put("alerts",        alerts);

            if (includeAll) {
                result.put("breakdown", breakdown);
                JSONObject sd = new JSONObject();
                sd.put("formula",      "score = (cpu*25 + ram*25 + disk*20 + net*10 + temp*20) / totalWeight");
                sd.put("tempIncluded", tempOk);
                sd.put("totalWeight",  fmt1(totalWeight * 100) + "%");
                sd.put("cpuSubScore",  Math.round(cpuScore));
                sd.put("ramSubScore",  Math.round(ramScore));
                sd.put("diskSubScore", Math.round(diskScore));
                sd.put("netSubScore",  Math.round(netScore));
                sd.put("tempSubScore", tempOk ? Math.round(tempScore) : "N/A");
                result.put("scoreDetail", sd);
            }

            return CollectorResult.ok(getName(), result);

        } catch (Exception e) {
            logger.error("Error occurred while collecting system health", e);
            JSONObject result = new JSONObject();
            result.put("error", e.getMessage());
            return CollectorResult.failure(getName(), result);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Score engine
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Sub-score 0–100 non lineare:
     *   0% → 100   (perfetto)
     *   warning% → 70   (inizio degradazione)
     *   critical% → 20  (zona critica)
     *   100% → 0   (saturazione totale)
     */
    private double score(double value, double warn, double crit) {
        if (value <= 0)    return 100.0;
        if (value >= 100)  return 0.0;
        if (value >= crit) {
            double t = (value - crit) / (100.0 - crit);
            return 20.0 * (1.0 - t);
        }
        if (value >= warn) {
            double t = (value - warn) / (crit - warn);
            return 70.0 - 50.0 * t;
        }
        double t = value / warn;
        return 100.0 - 30.0 * t;
    }

    private String status(double value, double warn, double crit) {
        if (value >= crit) return "CRITICAL";
        if (value >= warn) return "WARNING";
        return "HEALTHY";
    }

    // ════════════════════════════════════════════════════════════════════════
    // Samplers
    // ════════════════════════════════════════════════════════════════════════

    private double sampleCpu(CentralProcessor cpu) {
        cpu.getSystemCpuLoad(0);
        try { Thread.sleep(CPU_SAMPLE_MS); } catch (InterruptedException ignored) {}
        double load = cpu.getSystemCpuLoad(CPU_SAMPLE_MS);
        return Double.isNaN(load) ? 0.0 : Math.max(0.0, Math.min(load * 100.0, 100.0));
    }

    private long[] sampleNetErrors(SystemInfo si) {
        List<NetworkIF> ifaces = si.getHardware().getNetworkIFs();
        Map<String, long[]> before = new HashMap<>();
        for (NetworkIF iface : ifaces) {
            iface.updateAttributes();
            before.put(iface.getName(), new long[]{
                iface.getInErrors(), iface.getOutErrors(), iface.getInDrops()
            });
        }
        try { Thread.sleep(NET_SAMPLE_MS); } catch (InterruptedException ignored) {}

        long errors = 0L, drops = 0L;
        for (NetworkIF iface : ifaces) {
            iface.updateAttributes();
            long[] b = before.getOrDefault(iface.getName(), new long[]{0, 0, 0});
            errors += Math.max(0, (iface.getInErrors() + iface.getOutErrors()) - (b[0] + b[1]));
            drops  += Math.max(0, iface.getInDrops() - b[2]);
        }
        return new long[]{ errors, drops };
    }

    // ════════════════════════════════════════════════════════════════════════
    // Formatting helpers
    // ════════════════════════════════════════════════════════════════════════

    private String fmt1(double v)  { return String.format("%.1f", v); }
    private String pct(double w)   { return (int)(w * 100) + "%"; }

    @Override
    public Map<String, Class<?>> getAcceptedParameters() {
        return Map.of(
            "includeAll",  Boolean.class,
            "strictMode",  Boolean.class
        );
    }

    public static void main(String[] args) {
        CollectorHealth collector = new CollectorHealth();
        collector.includeAll = true;
        collector.strictMode = false;

        System.out.println("=== Health Collector Test ===");
        CollectorResult result = collector.collect();
        System.out.println(result.getResult().toJSONString());
    }
}