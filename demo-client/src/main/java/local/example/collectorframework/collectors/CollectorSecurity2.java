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
import oshi.hardware.NetworkIF;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

/**
 * Security Anomaly Detection Collector.
 *
 * Non è un antivirus né un IDS reale: analizza metriche di sistema (CPU,
 * processi, rete) e le confronta con soglie per segnalare comportamenti
 * anomali che potrebbero indicare un attacco in corso (cryptominer,
 * exfiltration, port scanner, fork bomb, ecc.).
 *
 * ── Strategia di detection in tre livelli ────────────────────────────────────
 *
 *   LEVEL 1 — Soglie singole (sempre attivo)
 *     CPU totale > CPU_HIGH_THRESHOLD           → alert CPU
 *     Processo con CPU% > PROC_CPU_THRESHOLD    → alert processo sospetto
 *     Network spike > NET_SPIKE_THRESHOLD       → alert traffico anomalo
 *
 *   LEVEL 2 — Correlazione (se includeAll = true)
 *     CPU alta + spike upload                   → possibile C2 / exfiltration
 *     CPU alta + processo sospetto              → possibile cryptominer
 *     Upload elevato con CPU bassa              → exfiltration silente
 *
 *   LEVEL 3 — Euristiche processo (se includeAll = true)
 *     Nome processo solo numerico               → tecnica di camuffamento malware
 *     Path eseguibile vuoto                     → processo iniettato in memoria
 *     Troppe istanze dello stesso eseguibile    → possibile fork bomb / worm
 *
 * ── Compatibilità OSHI ───────────────────────────────────────────────────────
 *   CPU load   : getSystemCpuLoad(nanos)          stabile da OSHI 5.x
 *   CPU proc   : userTime + kernelTime / upTime   no tick API instabili
 *   Rete       : updateAttributes() + getBytesRecv/Sent  stabile da OSHI 5.x
 *   Processi   : getProcesses()                   stabile da OSHI 5.x
 *
 * Parametri via reflection:
 *   includeAll → abilita livelli 2 e 3
 */
@AutoService(Collector.class)
@CollectorMetadata(
    name        = "hardware.security",
    description = "Anomaly detection su CPU, processi e rete: soglie, correlazione e euristiche",
    tags        = {"hardware", "security", "anomaly"}
)
public class CollectorSecurity2 implements Collector {
    private static final Logger logger = LoggerFactory.getLogger(CollectorSecurity2.class);

    // With reflective modify those values on core
    boolean includeAll;

    // ── Soglie ────────────────────────────────────────────────────────────────
    private static final double CPU_HIGH_THRESHOLD   = 0.85;
    private static final double CPU_MEDIUM_THRESHOLD = 0.60;
    private static final double PROC_CPU_THRESHOLD   = 40.0;
    private static final long   NET_SPIKE_DOWN_KB    = 10_240L;
    private static final long   NET_SPIKE_UP_KB      = 5_120L;
    private static final long   NET_SILENT_EXFIL_KB  = 2_048L;
    private static final int    MAX_CLONE_COUNT      = 15;
    private static final int    MAX_SUSPICIOUS_PROCS = 5;
    private static final long   CPU_SAMPLE_MS        = 500L;
    private static final long   NET_SAMPLE_MS        = 1_000L;

    // ── Whitelist processi di sistema ─────────────────────────────────────────
    private static final Set<String> SYSTEM_WHITELIST = new HashSet<>(Arrays.asList(
        "idle", "system", "registry", "smss.exe", "csrss.exe", "wininit.exe",
        "winlogon.exe", "services.exe", "lsass.exe", "svchost.exe", "dwm.exe",
        "explorer.exe", "taskhostw.exe", "sihost.exe", "fontdrvhost.exe",
        "memory compression", "secure system",
        "kernel_task", "launchd", "kthreadd", "systemd", "init", "dbus-daemon",
        "xorg", "xwayland"
    ));

    @Override
    public String getName() { return "hardware.security"; }

    @Override
    @SuppressWarnings("unchecked")
    public CollectorResult collect() {
        try {
            SystemInfo       si         = new SystemInfo();
            CentralProcessor cpu        = si.getHardware().getProcessor();
            OperatingSystem  os         = si.getOperatingSystem();
            int              logicCores = cpu.getLogicalProcessorCount();

            JSONArray  alerts = new JSONArray();
            JSONObject result = new JSONObject();

            // ════════════════════════════════════════════════════════════════
            // LEVEL 1A — CPU totale
            // ════════════════════════════════════════════════════════════════
            double cpuLoad = sampleCpuLoad(cpu);

            if (cpuLoad > CPU_HIGH_THRESHOLD) {
                alerts.add(String.format(
                    "[CPU] Utilizzo CPU critico: %.1f%% (soglia %.0f%%) — possibile DoS locale, cryptominer o processo runaway",
                    cpuLoad * 100, CPU_HIGH_THRESHOLD * 100));
            }

            // ════════════════════════════════════════════════════════════════
            // LEVEL 1B — Processi ad alto consumo CPU
            // ════════════════════════════════════════════════════════════════
            List<OSProcess> allProcs     = os.getProcesses();
            List<String>    suspProcs    = new ArrayList<>();
            Map<String, Integer> exeCount = new HashMap<>();

            for (OSProcess proc : allProcs) {
                String name = proc.getName();
                if (name == null || name.isBlank()) continue;

                if (includeAll) {
                    exeCount.merge(name.toLowerCase(Locale.ROOT), 1, Integer::sum);
                }

                if (isSystemProcess(name)) continue;

                double procCpuPct = computeProcCpuPct(proc, logicCores);
                if (procCpuPct > PROC_CPU_THRESHOLD
                        && suspProcs.size() < MAX_SUSPICIOUS_PROCS) {
                    suspProcs.add(String.format(
                        "[PROC] Processo ad alto CPU: \"%s\" (PID %d) — %.1f%% CPU",
                        name, proc.getProcessID(), procCpuPct));
                }
            }
            alerts.addAll(suspProcs);

            // ════════════════════════════════════════════════════════════════
            // LEVEL 1C — Spike di rete
            // ════════════════════════════════════════════════════════════════
            long[] netKbps = sampleNetworkKbps(si);
            long downKb    = netKbps[0];
            long upKb      = netKbps[1];

            if (downKb > NET_SPIKE_DOWN_KB) {
                alerts.add(String.format(
                    "[NET] Spike download anomalo: %s — possibile C2 payload download o DDoS reflection",
                    formatKbps(downKb)));
            }
            if (upKb > NET_SPIKE_UP_KB) {
                alerts.add(String.format(
                    "[NET] Spike upload anomalo: %s — possibile data exfiltration o botnet",
                    formatKbps(upKb)));
            }

            // ════════════════════════════════════════════════════════════════
            // LEVEL 2 — Correlazione
            // ════════════════════════════════════════════════════════════════
            if (includeAll) {
                boolean cpuHigh   = cpuLoad > CPU_HIGH_THRESHOLD;
                boolean cpuMedium = cpuLoad > CPU_MEDIUM_THRESHOLD;
                boolean netUp     = upKb   > NET_SPIKE_UP_KB;
                boolean netDown   = downKb > NET_SPIKE_DOWN_KB;
                boolean hasProc   = !suspProcs.isEmpty();

                if (cpuHigh && netUp) {
                    alerts.add(String.format(
                        "[CORR] CPU critica (%.1f%%) + upload anomalo (%s): pattern C2 / exfiltration attiva",
                        cpuLoad * 100, formatKbps(upKb)));
                }
                if (cpuMedium && hasProc && netUp) {
                    alerts.add(
                        "[CORR] CPU elevata + processo sospetto + upload anomalo: pattern cryptominer con pool remoto");
                }
                if (cpuHigh && hasProc && !netUp && !netDown) {
                    alerts.add(
                        "[CORR] CPU critica + processo sospetto + rete ok: possibile cryptominer locale o loop");
                }
                if (!cpuMedium && upKb > NET_SILENT_EXFIL_KB) {
                    alerts.add(String.format(
                        "[CORR] Upload sostenuto (%s) con CPU bassa (%.1f%%): possibile exfiltration silente",
                        formatKbps(upKb), cpuLoad * 100));
                }

                // ════════════════════════════════════════════════════════════
                // LEVEL 3 — Euristiche processo
                // ════════════════════════════════════════════════════════════
                for (OSProcess proc : allProcs) {
                    String name = proc.getName();
                    if (name == null || name.isBlank() || isSystemProcess(name)) continue;

                    // Nome solo numerico
                    String base = name.replaceAll("(?i)\\.exe$", "").trim();
                    if (base.matches("\\d+")) {
                        alerts.add(String.format(
                            "[PROC] Nome numerico: \"%s\" (PID %d) — tecnica comune di camuffamento malware",
                            name, proc.getProcessID()));
                    }

                    // Path vuoto = possibile processo iniettato
                    String path = proc.getPath();
                    if (path == null || path.isBlank()) {
                        alerts.add(String.format(
                            "[PROC] Processo senza path: \"%s\" (PID %d) — possibile processo iniettato o nascosto",
                            name, proc.getProcessID()));
                    }
                }

                // Fork-bomb / worm
                exeCount.forEach((exe, count) -> {
                    if (count > MAX_CLONE_COUNT && !isSystemProcess(exe)) {
                        alerts.add(String.format(
                            "[PROC] %d istanze di \"%s\" — possibile fork bomb o worm",
                            count, exe));
                    }
                });
            }

            // ── Output ────────────────────────────────────────────────────────
            result.put("alertCount",  alerts.size());
            result.put("alerts",      alerts);
            result.put("status",      alerts.isEmpty() ? "CLEAN" : "SUSPICIOUS");
            result.put("cpuLoadPct",  String.format("%.1f", cpuLoad * 100));
            result.put("netDownKbps", downKb);
            result.put("netUpKbps",   upKb);

            return CollectorResult.ok(getName(), result);

        } catch (Exception e) {
            logger.error("Error occurred during security anomaly detection", e);
            JSONObject result = new JSONObject();
            result.put("error", e.getMessage());
            return CollectorResult.failure(getName(), result);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════════════

    private double sampleCpuLoad(CentralProcessor cpu) {
        cpu.getSystemCpuLoad(0);
        try { Thread.sleep(CPU_SAMPLE_MS); } catch (InterruptedException ignored) {}
        double load = cpu.getSystemCpuLoad(CPU_SAMPLE_MS);
        return Double.isNaN(load) ? 0.0 : Math.max(0.0, Math.min(load, 1.0));
    }

    private double computeProcCpuPct(OSProcess proc, int logicCores) {
        long upTimeSec = proc.getUpTime() / 1000L;
        if (upTimeSec <= 0) return 0.0;
        long cpuTimeSec = (proc.getUserTime() + proc.getKernelTime()) / 1000L;
        return Math.min((double) cpuTimeSec / upTimeSec * 100.0 / logicCores, 100.0);
    }

    private long[] sampleNetworkKbps(SystemInfo si) {
        List<NetworkIF> ifaces = si.getHardware().getNetworkIFs();
        Map<String, long[]> before = new HashMap<>();
        for (NetworkIF iface : ifaces) {
            iface.updateAttributes();
            before.put(iface.getName(),
                new long[]{ iface.getBytesRecv(), iface.getBytesSent() });
        }
        try { Thread.sleep(NET_SAMPLE_MS); } catch (InterruptedException ignored) {}

        long downBytes = 0L, upBytes = 0L;
        for (NetworkIF iface : ifaces) {
            iface.updateAttributes();
            long[] b = before.getOrDefault(iface.getName(), new long[]{0L, 0L});
            downBytes += Math.max(0, iface.getBytesRecv() - b[0]);
            upBytes   += Math.max(0, iface.getBytesSent() - b[1]);
        }
        double seconds = NET_SAMPLE_MS / 1000.0;
        return new long[]{
            (long)(downBytes / 1024.0 / seconds),
            (long)(upBytes   / 1024.0 / seconds)
        };
    }

    private boolean isSystemProcess(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT).trim();
        if (SYSTEM_WHITELIST.contains(lower)) return true;
        if (lower.startsWith("[") && lower.endsWith("]")) return true;
        return false;
    }

    private String formatKbps(long kbps) {
        return kbps >= 1024
            ? String.format("%.1f MB/s", kbps / 1024.0)
            : kbps + " KB/s";
    }

    @Override
    public Map<String, Class<?>> getAcceptedParameters() {
        return Map.of("includeAll", Boolean.class);
    }

    public static void main(String[] args) {
        CollectorSecurity2 collector = new CollectorSecurity2();
        collector.includeAll = true;

        System.out.println("=== Security Anomaly Collector Test ===");
        System.out.println("Campionamento in corso (~1.5s)...");
        CollectorResult result = collector.collect();
        System.out.println(result.getResult().toJSONString());
    }
}