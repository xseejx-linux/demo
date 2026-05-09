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
 * Non è un antivirus né un IDS reale: analizza metriche di sistema (CPU, processi,
 * rete) e le confronta con soglie per segnalare comportamenti anomali che potrebbero
 * indicare un attacco in corso (cryptominer, exfiltration, port scanner, ecc.).
 *
 * Strategia di detection in tre livelli:
 *
 *   LEVEL 1 — Soglie singole
 *     CPU totale > CPU_HIGH_THRESHOLD           → alert CPU
 *     Processo con CPU% > PROC_CPU_THRESHOLD    → alert processo sospetto
 *     Network spike > NET_SPIKE_THRESHOLD_KB    → alert traffico anomalo
 *
 *   LEVEL 2 — Correlazione (se includeAll = true)
 *     CPU alta + spike di rete                  → possibile C2 / exfiltration
 *     CPU alta + processo ignoto ad alto consumo → possibile cryptominer
 *     Spike upload con CPU bassa                → possibile data exfiltration silente
 *
 *   LEVEL 3 — Euristiche di processo
 *     Processo con nome solo numerico           → potenziale malware (es. "12345.exe")
 *     Processo con path vuoto (solo Windows)    → possibile processo nascosto
 *     Più di MAX_CLONE_COUNT istanze dello stesso eseguibile → possibile fork bomb
 */
@AutoService(Collector.class)
@CollectorMetadata(
    name        = "system.security",
    description = "Anomaly detection basata su soglie e correlazione: CPU, processi sospetti, spike di rete",
    tags        = {"system", "security", "anomaly"}
)
public class CollectorSecurity implements Collector {
    private static final Logger logger = LoggerFactory.getLogger(CollectorSecurity.class);

    // ── Parametro principale ────────────────────────────────────────────────
    // Se false: solo alert di livello 1 (soglie singole, rapido)
    // Se true:  anche livello 2 (correlazione) e livello 3 (euristiche processo)
    private boolean includeAll;

    // ── Soglie di anomalia ──────────────────────────────────────────────────

    /** CPU totale (0.0–1.0) sopra cui scatta l'alert. Default: 85% */
    private static final double CPU_HIGH_THRESHOLD = 0.85;

    /** CPU totale (0.0–1.0) considerata "moderatamente alta" per correlazioni. Default: 60% */
    private static final double CPU_MEDIUM_THRESHOLD = 0.60;

    /** % CPU di un singolo processo sopra cui è sospetto. Default: 40% */
    private static final double PROC_CPU_THRESHOLD = 40.0;

    /** Spike download anomalo in KB/s. Default: 10 MB/s = 10240 KB/s */
    private static final long NET_SPIKE_DOWN_KB = 10_240L;

    /** Spike upload anomalo in KB/s. Default: 5 MB/s = 5120 KB/s */
    private static final long NET_SPIKE_UP_KB = 5_120L;

    /** Upload anomalo con CPU bassa (possibile exfiltration silenziosa) in KB/s. Default: 2 MB/s */
    private static final long NET_SILENT_EXFIL_KB = 2_048L;

    /** Numero massimo di istanze dello stesso eseguibile prima di segnalarlo. Default: 15 */
    private static final int MAX_CLONE_COUNT = 15;

    /** Quanti processi sospetti mostrare al massimo nell'output. Default: 5 */
    private static final int MAX_SUSPICIOUS_PROCS = 5;

    /** Intervallo di campionamento CPU in ms (OSHI ha bisogno di due letture). Default: 500ms */
    private static final long CPU_SAMPLE_INTERVAL_MS = 500L;

    /** Intervallo di campionamento rete in ms. Default: 1000ms */
    private static final long NET_SAMPLE_INTERVAL_MS = 1_000L;

    // ── Processi di sistema da ignorare (case-insensitive) ──────────────────
    private static final Set<String> SYSTEM_PROCESS_WHITELIST = new HashSet<>(Arrays.asList(
        "idle", "system", "registry", "smss.exe", "csrss.exe", "wininit.exe",
        "winlogon.exe", "services.exe", "lsass.exe", "svchost.exe", "dwm.exe",
        "explorer.exe", "taskhostw.exe", "sihost.exe", "fontdrvhost.exe",
        "kernel_task", "launchd", "kthreadd", "[kworker]", "ksoftirqd",
        "migration", "rcu_sched", "systemd", "init", "dbus-daemon",
        "Xorg", "Xwayland"
    ));

    @Override
    public String getName() { return "system.security"; }

    @Override
    @SuppressWarnings("unchecked")
    public CollectorResult collect() {
        try {
            SystemInfo si     = new SystemInfo();
            CentralProcessor cpu = si.getHardware().getProcessor();
            OperatingSystem  os  = si.getOperatingSystem();

            JSONArray  alerts    = new JSONArray();
            JSONObject result    = new JSONObject();

            // ════════════════════════════════════════════════════════════════
            // LEVEL 1 — Soglie singole
            // ════════════════════════════════════════════════════════════════

            // ── 1a. CPU totale ───────────────────────────────────────────────
            double cpuLoad = sampleCpuLoad(cpu);

            if (cpuLoad > CPU_HIGH_THRESHOLD) {
                alerts.add(String.format(
                    "[CPU] Utilizzo CPU critico: %.1f%% (soglia: %.0f%%) — possibile processo malevolo o attacco DoS locale",
                    cpuLoad * 100, CPU_HIGH_THRESHOLD * 100));
            }

            // ── 1b. Processi ad alto consumo CPU ────────────────────────────
            List<OSProcess> allProcs = os.getProcesses();
            List<String>    suspiciousProcs = new ArrayList<>();
            Map<String, Integer> executableCount = new HashMap<>();

            for (OSProcess proc : allProcs) {
                String name = proc.getName();
                if (name == null || name.isBlank()) continue;

                // Conta le istanze per la fork-bomb detection (level 3)
                if (includeAll) {
                    executableCount.merge(name.toLowerCase(), 1, Integer::sum);
                }

                // Salta whitelist di sistema
                if (isSystemProcess(name)) continue;

                // CPU% di questo processo (OSHI: getProcessCpuLoadBetweenTicks o cumulative)
                double procCpuPct = computeProcessCpuPercent(proc, cpu.getLogicalProcessorCount());

                if (procCpuPct > PROC_CPU_THRESHOLD && suspiciousProcs.size() < MAX_SUSPICIOUS_PROCS) {
                    suspiciousProcs.add(String.format(
                        "[PROC] Processo sospetto ad alto CPU: \"%s\" (PID %d) — CPU: %.1f%%",
                        name, proc.getProcessID(), procCpuPct));
                }
            }
            alerts.addAll(suspiciousProcs);

            // ── 1c. Spike di rete ────────────────────────────────────────────
            NetworkSample netSample = sampleNetworkTraffic(si);
            long downKb = netSample.downKbps;
            long upKb   = netSample.upKbps;

            if (downKb > NET_SPIKE_DOWN_KB) {
                alerts.add(String.format(
                    "[NET] Spike download anomalo: %s — possibile C2 download, aggiornamento malevolo o attacco DDoS reflection",
                    formatKbps(downKb)));
            }
            if (upKb > NET_SPIKE_UP_KB) {
                alerts.add(String.format(
                    "[NET] Spike upload anomalo: %s — possibile data exfiltration o partecipazione a botnet",
                    formatKbps(upKb)));
            }

            // ════════════════════════════════════════════════════════════════
            // LEVEL 2 — Correlazione (solo se includeAll = true)
            // ════════════════════════════════════════════════════════════════
            if (includeAll) {

                boolean cpuHigh   = cpuLoad > CPU_HIGH_THRESHOLD;
                boolean cpuMedium = cpuLoad > CPU_MEDIUM_THRESHOLD;
                boolean netDown   = downKb > NET_SPIKE_DOWN_KB;
                boolean netUp     = upKb   > NET_SPIKE_UP_KB;
                boolean hasSuspiciousProc = !suspiciousProcs.isEmpty();

                // CPU alta + spike upload → C2 / exfiltration attiva
                if (cpuHigh && netUp) {
                    alerts.add(String.format(
                        "[CORR] CPU alta (%.1f%%) + upload anomalo (%s): scenario coerente con C2 command-and-control o exfiltration attiva",
                        cpuLoad * 100, formatKbps(upKb)));
                }

                // CPU alta + processo sospetto + upload → cryptominer con C2
                if (cpuMedium && hasSuspiciousProc && netUp) {
                    alerts.add(
                        "[CORR] CPU elevata + processo ad alto consumo + upload anomalo: pattern compatibile con cryptominer connesso a pool remoto");
                }

                // CPU alta + processo sospetto, senza rete → cryptominer locale
                if (cpuHigh && hasSuspiciousProc && !netUp && !netDown) {
                    alerts.add(
                        "[CORR] CPU critica con processo sospetto e rete nella norma: possibile cryptominer locale o processo in loop infinito");
                }

                // Upload anomalo con CPU nella norma → exfiltration silente
                if (!cpuMedium && upKb > NET_SILENT_EXFIL_KB) {
                    alerts.add(String.format(
                        "[CORR] Upload sostenuto (%s) con CPU nella norma (%.1f%%): possibile exfiltration silente in background",
                        formatKbps(upKb), cpuLoad * 100));
                }

                // ════════════════════════════════════════════════════════════
                // LEVEL 3 — Euristiche di processo
                // ════════════════════════════════════════════════════════════

                for (OSProcess proc : allProcs) {
                    String name = proc.getName();
                    if (name == null || name.isBlank() || isSystemProcess(name)) continue;

                    // Processo con nome solo numerico (es. "23847", "1337.exe")
                    String baseName = name.replaceAll("(?i)\\.exe$", "").trim();
                    if (baseName.matches("\\d+")) {
                        alerts.add(String.format(
                            "[PROC] Processo con nome puramente numerico: \"%s\" (PID %d) — tecnica comune per camuffare malware",
                            name, proc.getProcessID()));
                    }

                    // Processo senza path (su Windows indica processo nascosto o iniettato)
                    String path = proc.getPath();
                    if ((path == null || path.isBlank()) && !isSystemProcess(name)) {
                        alerts.add(String.format(
                            "[PROC] Processo senza path eseguibile: \"%s\" (PID %d) — possibile processo iniettato o nascosto",
                            name, proc.getProcessID()));
                    }
                }

                // Fork-bomb / processo replicato eccessivamente
                executableCount.forEach((exe, count) -> {
                    if (count > MAX_CLONE_COUNT && !isSystemProcess(exe)) {
                        alerts.add(String.format(
                            "[PROC] %d istanze di \"%s\" in esecuzione — possibile fork bomb o worm che si replica",
                            count, exe));
                    }
                });
            }

            // ── Output finale ────────────────────────────────────────────────
            result.put("alerts", alerts);
            result.put("alertCount", alerts.size());
            result.put("status", alerts.isEmpty() ? "CLEAN" : "SUSPICIOUS");
            result.put("cpuLoadPercent", String.format("%.1f", cpuLoad * 100));
            result.put("netDownKbps", downKb);
            result.put("netUpKbps", upKb);

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

    /**
     * Campiona il carico CPU con due tick distanziati di CPU_SAMPLE_INTERVAL_MS.
     * Restituisce un valore 0.0–1.0.
     */
    private double sampleCpuLoad(CentralProcessor cpu) {
        // Prima lettura — OSHI memorizza internamente i tick precedenti
        cpu.getSystemCpuLoad(0);
        try { Thread.sleep(CPU_SAMPLE_INTERVAL_MS); } catch (InterruptedException ignored) {}
        // Seconda lettura — restituisce il carico tra le due chiamate
        return cpu.getSystemCpuLoad(CPU_SAMPLE_INTERVAL_MS);
    }

    /**
     * Calcola la % CPU di un processo rispetto ai core logici disponibili.
     * Usa il tempo CPU cumulativo del processo rispetto all'uptime.
     * Non richiede due campionamenti → veloce ma meno preciso di getProcessCpuLoadBetweenTicks.
     */
    private double computeProcessCpuPercent(OSProcess proc, int logicalCores) {
        long upTimeSec  = proc.getUpTime() / 1000L;
        if (upTimeSec <= 0) return 0.0;
        long cpuTimeSec = (proc.getUserTime() + proc.getKernelTime()) / 1000L;
        double pct = (double) cpuTimeSec / upTimeSec * 100.0;
        // Normalizza sul numero di core (OSHI restituisce % aggregata)
        return Math.min(pct / logicalCores, 100.0);
    }

    /**
     * Campiona il traffico di rete su tutte le interfacce attive.
     * Esegue due letture distanziate di NET_SAMPLE_INTERVAL_MS e calcola KB/s.
     */
    private NetworkSample sampleNetworkTraffic(SystemInfo si) {
        List<NetworkIF> ifaces = si.getHardware().getNetworkIFs();

        // Prima lettura
        Map<String, long[]> before = new HashMap<>();
        for (NetworkIF iface : ifaces) {
            iface.updateAttributes();
            before.put(iface.getName(), new long[]{iface.getBytesRecv(), iface.getBytesSent()});
        }

        try { Thread.sleep(NET_SAMPLE_INTERVAL_MS); } catch (InterruptedException ignored) {}

        // Seconda lettura
        long totalDownBytes = 0L;
        long totalUpBytes   = 0L;
        for (NetworkIF iface : ifaces) {
            iface.updateAttributes();
            long[] b = before.getOrDefault(iface.getName(), new long[]{0L, 0L});
            long downDelta = Math.max(0, iface.getBytesRecv()  - b[0]);
            long upDelta   = Math.max(0, iface.getBytesSent()  - b[1]);
            totalDownBytes += downDelta;
            totalUpBytes   += upDelta;
        }

        // Il sample è durato NET_SAMPLE_INTERVAL_MS → calcola KB/s
        double seconds = NET_SAMPLE_INTERVAL_MS / 1000.0;
        return new NetworkSample(
            (long) (totalDownBytes / 1024.0 / seconds),
            (long) (totalUpBytes   / 1024.0 / seconds)
        );
    }

    /** Verifica se un nome di processo appartiene alla whitelist di sistema. */
    private boolean isSystemProcess(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase().trim();
        if (SYSTEM_PROCESS_WHITELIST.contains(lower)) return true;
        // Pattern Linux: thread del kernel tra parentesi quadre
        if (lower.startsWith("[") && lower.endsWith("]")) return true;
        return false;
    }

    /** Formatta KB/s in stringa leggibile (KB/s o MB/s). */
    private String formatKbps(long kbps) {
        if (kbps >= 1024) return String.format("%.1f MB/s", kbps / 1024.0);
        return kbps + " KB/s";
    }

    /** Semplice contenitore per il risultato del campionamento rete. */
    private static class NetworkSample {
        final long downKbps;
        final long upKbps;
        NetworkSample(long downKbps, long upKbps) {
            this.downKbps = downKbps;
            this.upKbps   = upKbps;
        }
    }

    @Override
    public Map<String, Class<?>> getAcceptedParameters() {
        return Map.of("includeAll", Boolean.class);
    }

    public static void main(String[] args) {
        CollectorSecurity collector = new CollectorSecurity();
        collector.includeAll = true;

        System.out.println("=== Security Anomaly Detection Collector Test ===");
        System.out.println("Campionamento in corso (CPU + rete ~1.5s)...");

        CollectorResult result = collector.collect();
        System.out.println(result.getResult().toJSONString());
    }
}