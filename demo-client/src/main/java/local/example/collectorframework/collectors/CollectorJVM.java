package local.example.collectorframework.collectors;

import java.lang.management.*;
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

/**
 * JVM Collector — monitoraggio interno della Java Virtual Machine.
 *
 * Utilizza esclusivamente le Java Management APIs (java.lang.management),
 * disponibili in ogni JDK/JRE senza dipendenze aggiuntive.
 *
 * Metriche raccolte:
 *   Heap        → usato, committed, max (in MB) + percentuale utilizzo
 *   Non-Heap    → metaspace e code cache (se includeAll = true)
 *   GC          → nome collector, collection count e tempo cumulativo per GC
 *   Thread      → totale, daemon, peak, started totale
 *   ClassLoader → classi caricate, scaricate, totale caricate dall'avvio
 *   CPU JVM     → % CPU del processo JVM corrente sull'intero sistema
 *   Uptime      → tempo di esecuzione della JVM in secondi
 *
 * Alert generati:
 *   [HEAP]    Heap > 90%  → rischio OutOfMemoryError imminente
 *   [HEAP]    Heap > 75%  → warning, GC frequente probabile
 *   [THREAD]  Thread > MAX_THREAD_THRESHOLD → possibile thread leak
 *   [GC]      GC count incrementato molto in poco tempo (alta frequenza GC)
 *   [GC]      Tempo GC > GC_TIME_THRESHOLD_PCT del tempo JVM → GC overhead
 *   [CPU]     CPU JVM > 80%
 *
 * Parametri via reflection:
 *   includeAll       → aggiunge non-heap, memory pools dettagliati, classloader
 *   includeGcDetail  → aggiunge metriche GC dettagliate per ogni collector
 */
@AutoService(Collector.class)
@CollectorMetadata(
    name        = "hardware.jvm",
    description = "Monitoraggio interno JVM: heap, GC, thread, classloader e CPU del processo",
    tags        = {"hardware", "jvm", "realtime"}
)
public class CollectorJVM implements Collector {
    private static final Logger logger = LoggerFactory.getLogger(CollectorJVM.class);

    // With reflective modify those values on core
    private boolean includeAll;
    private boolean includeGcDetail;

    // ── Soglie ──────────────────────────────────────────────────────────────
    private static final double HEAP_CRITICAL_PCT    = 90.0;
    private static final double HEAP_WARNING_PCT     = 75.0;
    private static final int    MAX_THREAD_THRESHOLD = 500;
    private static final double CPU_WARNING_PCT      = 80.0;

    /**
     * Soglia per "GC overhead": se il GC ha impiegato più di questa percentuale
     * del tempo totale JVM, la JVM sta spendendo troppo tempo a fare garbage collection.
     * JVM stessa lancia OutOfMemoryError a 98%, noi avvisiamo prima al 20%.
     */
    private static final double GC_TIME_THRESHOLD_PCT = 20.0;

    @Override
    public String getName() { return "hardware.jvm"; }

    @Override
    @SuppressWarnings("unchecked")
    public CollectorResult collect() {
        try {
            MemoryMXBean          memBean     = ManagementFactory.getMemoryMXBean();
            ThreadMXBean          threadBean  = ManagementFactory.getThreadMXBean();
            ClassLoadingMXBean    classBean   = ManagementFactory.getClassLoadingMXBean();
            RuntimeMXBean         runtimeBean = ManagementFactory.getRuntimeMXBean();
            OperatingSystemMXBean osBean      = ManagementFactory.getOperatingSystemMXBean();
            List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();

            JSONObject result    = new JSONObject();
            JSONArray  alertArray = new JSONArray();

            long uptimeMs = runtimeBean.getUptime();

            // ════════════════════════════════════════════════════════════════
            // 1. HEAP MEMORY
            // ════════════════════════════════════════════════════════════════
            MemoryUsage heapUsage = memBean.getHeapMemoryUsage();

            long heapUsedBytes  = heapUsage.getUsed();
            long heapCommBytes  = heapUsage.getCommitted();
            long heapMaxBytes   = heapUsage.getMax();

            long heapUsedMB  = heapUsedBytes  / (1024 * 1024);
            long heapCommMB  = heapCommBytes  / (1024 * 1024);
            long heapMaxMB   = heapMaxBytes   / (1024 * 1024);

            double heapPct = heapMaxBytes > 0
                ? (double) heapUsedBytes / heapMaxBytes * 100.0
                : 0.0;

            result.put("heapUsedMB",       heapUsedMB);
            result.put("heapCommittedMB",  heapCommMB);
            result.put("heapMaxMB",        heapMaxMB);
            result.put("heapUsedPct",      String.format("%.1f", heapPct));
            result.put("heapStatus",       heapStatus(heapPct));

            if (heapPct >= HEAP_CRITICAL_PCT) {
                alertArray.add(String.format(
                    "[HEAP] Utilizzo heap critico: %.1f%% (%d MB / %d MB) — rischio OutOfMemoryError imminente",
                    heapPct, heapUsedMB, heapMaxMB));
            } else if (heapPct >= HEAP_WARNING_PCT) {
                alertArray.add(String.format(
                    "[HEAP] Utilizzo heap elevato: %.1f%% (%d MB / %d MB) — possibile GC frequente",
                    heapPct, heapUsedMB, heapMaxMB));
            }

            // ── Non-Heap (Metaspace + Code Cache) ────────────────────────────
            if (includeAll) {
                MemoryUsage nonHeap = memBean.getNonHeapMemoryUsage();
                JSONObject nonHeapObj = new JSONObject();
                nonHeapObj.put("usedMB",      nonHeap.getUsed()      / (1024 * 1024));
                nonHeapObj.put("committedMB", nonHeap.getCommitted() / (1024 * 1024));
                long nhMax = nonHeap.getMax();
                nonHeapObj.put("maxMB", nhMax > 0 ? nhMax / (1024 * 1024) : "unlimited");
                result.put("nonHeap", nonHeapObj);

                // Memory pools dettagliati (Metaspace, Eden, Survivor, Old Gen, ecc.)
                JSONArray poolsArray = new JSONArray();
                for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                    MemoryUsage u = pool.getUsage();
                    JSONObject p = new JSONObject();
                    p.put("name",      pool.getName());
                    p.put("type",      pool.getType().name());
                    p.put("usedMB",    u.getUsed() / (1024 * 1024));
                    long pMax = u.getMax();
                    p.put("maxMB", pMax > 0 ? pMax / (1024 * 1024) : "unlimited");
                    poolsArray.add(p);
                }
                result.put("memoryPools", poolsArray);
            }

            // ════════════════════════════════════════════════════════════════
            // 2. GARBAGE COLLECTOR
            // ════════════════════════════════════════════════════════════════
            long totalGcCount   = 0L;
            long totalGcTimeMs  = 0L;
            JSONArray gcArray   = new JSONArray();

            for (GarbageCollectorMXBean gc : gcBeans) {
                long count   = gc.getCollectionCount();
                long timeMs  = gc.getCollectionTime();

                if (count >= 0) totalGcCount  += count;
                if (timeMs >= 0) totalGcTimeMs += timeMs;

                if (includeGcDetail) {
                    JSONObject gcObj = new JSONObject();
                    gcObj.put("name",        gc.getName());
                    gcObj.put("collections", count  >= 0 ? count  : "N/A");
                    gcObj.put("timeMs",      timeMs >= 0 ? timeMs : "N/A");
                    // Stima frequenza: collezioni al secondo dall'avvio
                    if (count > 0 && uptimeMs > 0) {
                        gcObj.put("collectionsPerSec",
                            String.format("%.3f", (double) count / (uptimeMs / 1000.0)));
                    }
                    gcArray.add(gcObj);
                }
            }

            result.put("gcTotalCollections", totalGcCount);
            result.put("gcTotalTimeMs",      totalGcTimeMs);

            if (includeGcDetail) {
                result.put("gcCollectors", gcArray);
            }

            // Alert GC overhead: % tempo JVM speso in GC
            if (uptimeMs > 0 && totalGcTimeMs > 0) {
                double gcOverheadPct = (double) totalGcTimeMs / uptimeMs * 100.0;
                result.put("gcOverheadPct", String.format("%.2f", gcOverheadPct));

                if (gcOverheadPct > GC_TIME_THRESHOLD_PCT) {
                    alertArray.add(String.format(
                        "[GC] GC overhead elevato: la JVM ha trascorso il %.1f%% del suo tempo in garbage collection " +
                        "(soglia: %.0f%%) — possibile memory leak o heap sottodimensionato",
                        gcOverheadPct, GC_TIME_THRESHOLD_PCT));
                }
            }

            // ════════════════════════════════════════════════════════════════
            // 3. THREAD
            // ════════════════════════════════════════════════════════════════
            int threadCount      = threadBean.getThreadCount();
            int peakCount        = threadBean.getPeakThreadCount();
            long startedTotal    = threadBean.getTotalStartedThreadCount();

            // getDaemonCount() non è definito nell'interfaccia standard ThreadMXBean —
            // lo leggiamo dal ThreadGroup root, che è sempre disponibile.
            int daemonCount = countDaemonThreads();

            result.put("threadCount",        threadCount);
            result.put("threadDaemonCount",  daemonCount);
            result.put("threadPeakCount",    peakCount);
            result.put("threadStartedTotal", startedTotal);

            if (threadCount > MAX_THREAD_THRESHOLD) {
                alertArray.add(String.format(
                    "[THREAD] Numero thread elevato: %d (soglia: %d) — possibile thread leak o pool non limitato",
                    threadCount, MAX_THREAD_THRESHOLD));
            }

            // Blocco thread (deadlock detection)
            if (includeAll) {
                long[] deadlockedIds = threadBean.findDeadlockedThreads();
                if (deadlockedIds != null && deadlockedIds.length > 0) {
                    result.put("deadlockedThreads", deadlockedIds.length);
                    alertArray.add(String.format(
                        "[THREAD] Rilevati %d thread in deadlock — la JVM potrebbe bloccarsi",
                        deadlockedIds.length));
                } else {
                    result.put("deadlockedThreads", 0);
                }
            }

            // ════════════════════════════════════════════════════════════════
            // 4. CLASS LOADER
            // ════════════════════════════════════════════════════════════════
            if (includeAll) {
                JSONObject clObj = new JSONObject();
                clObj.put("loadedClasses",        classBean.getLoadedClassCount());
                clObj.put("totalLoadedClasses",   classBean.getTotalLoadedClassCount());
                clObj.put("unloadedClasses",      classBean.getUnloadedClassCount());
                result.put("classLoader", clObj);
            } else {
                result.put("loadedClassCount", classBean.getLoadedClassCount());
            }

            // ════════════════════════════════════════════════════════════════
            // 5. CPU JVM
            // ════════════════════════════════════════════════════════════════
            double cpuPct = -1.0;

            // com.sun.management.OperatingSystemMXBean espone getProcessCpuLoad()
            // disponibile su HotSpot (Oracle JDK, OpenJDK) ma non garantito su tutti i JVM
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOs) {
                double load = sunOs.getProcessCpuLoad();
                if (load >= 0) {
                    cpuPct = load * 100.0;
                    result.put("jvmCpuPct", String.format("%.1f", cpuPct));

                    if (cpuPct > CPU_WARNING_PCT) {
                        alertArray.add(String.format(
                            "[CPU] CPU JVM elevata: %.1f%% — processo Java sta saturando il processore",
                            cpuPct));
                    }
                } else {
                    result.put("jvmCpuPct", "N/A");
                }
            } else {
                // Fallback: carico di sistema (meno preciso, ma sempre disponibile)
                double sysLoad = osBean.getSystemLoadAverage();
                result.put("jvmCpuPct",     "N/A");
                result.put("systemLoadAvg", sysLoad >= 0
                    ? String.format("%.2f", sysLoad)
                    : "N/A");
            }

            // ════════════════════════════════════════════════════════════════
            // 6. UPTIME E INFO RUNTIME
            // ════════════════════════════════════════════════════════════════
            result.put("uptimeSec",  uptimeMs / 1000L);
            result.put("jvmName",    runtimeBean.getVmName());
            result.put("jvmVersion", runtimeBean.getSpecVersion());

            // ── Output finale ─────────────────────────────────────────────────
            result.put("alerts",     alertArray);
            result.put("alertCount", alertArray.size());

            return CollectorResult.ok(getName(), result);

        } catch (Exception e) {
            logger.error("Error occurred while collecting JVM information", e);
            JSONObject result = new JSONObject();
            result.put("error", e.getMessage());
            return CollectorResult.failure(getName(), result);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Conta i thread daemon attivi enumerando il ThreadGroup radice.
     * Usato al posto di ThreadMXBean.getDaemonCount() che non è parte
     * dell'interfaccia standard java.lang.management.ThreadMXBean.
     */
    private int countDaemonThreads() {
        try {
            // Risale al ThreadGroup root
            ThreadGroup root = Thread.currentThread().getThreadGroup();
            while (root.getParent() != null) root = root.getParent();

            // Alloca un array leggermente sovradimensionato per sicurezza
            Thread[] threads = new Thread[root.activeCount() + 64];
            int count = root.enumerate(threads, true);

            int daemonCount = 0;
            for (int i = 0; i < count; i++) {
                if (threads[i] != null && threads[i].isDaemon()) daemonCount++;
            }
            return daemonCount;
        } catch (Exception e) {
            logger.debug("Could not count daemon threads: {}", e.getMessage());
            return -1;
        }
    }

    private String heapStatus(double pct) {
        if (pct >= HEAP_CRITICAL_PCT) return "CRITICAL";
        if (pct >= HEAP_WARNING_PCT)  return "WARNING";
        return "OK";
    }

    @Override
    public Map<String, Class<?>> getAcceptedParameters() {
        return Map.of(
            "includeAll",      Boolean.class,  // non-heap, memory pools, classloader, deadlock
            "includeGcDetail", Boolean.class   // dettaglio per ogni GC collector
        );
    }

    public static void main(String[] args) {
        CollectorJVM collector = new CollectorJVM();
        collector.includeAll      = true;
        collector.includeGcDetail = true;

        System.out.println("=== JVM Collector Test ===");
        CollectorResult result = collector.collect();
        System.out.println(result.getResult().toJSONString());
    }
}