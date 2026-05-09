package local.example.collectorframework.collectors;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
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
 * Internet Connectivity Collector.
 *
 * Strategia di misurazione a tre livelli (usati in cascata):
 *
 *   LEVEL 1 — System ping (ICMP reale via ProcessBuilder)
 *     Esegue il comando ping nativo dell'OS: affidabile, nessun privilegio richiesto.
 *     Misura RTT reale e packet loss dal testo dell'output.
 *     Windows: ping -n PROBES -w TIMEOUT_MS <host>
 *     Linux/Mac: ping -c PROBES -W TIMEOUT_SEC <host>
 *
 *   LEVEL 2 — TCP Socket ping (fallback se ICMP bloccato dal firewall dell'host)
 *     Tenta una connessione TCP sulla porta 443 (HTTPS) verso ogni target.
 *     Misura il tempo di connessione come approssimazione della latenza.
 *     Nessuna dipendenza, funziona su tutti gli OS senza privilegi.
 *
 *   LEVEL 3 — DNS resolution fallback (ultimo resort)
 *     Risolve il nome host tramite DNS. Se la risoluzione funziona,
 *     la connessione Internet esiste. Non misura latenza reale.
 *
 * Target multipli:
 *   Viene effettuato il ping verso TUTTI i target configurati.
 *   Il risultato finale (pingMs, packetLoss) è la media dei target raggiungibili.
 *   Se almeno un target risponde → internetAvailable = true.
 *
 * Alert generati:
 *   [NET] Nessuna connessione Internet
 *   [NET] Latenza alta  (> PING_HIGH_MS)
 *   [NET] Latenza critica (> PING_CRITICAL_MS)
 *   [NET] Packet loss parziale (> 0% ma < 100%)
 *   [NET] Packet loss totale verso un target
 *
 * Parametri via reflection:
 *   includeAll    → aggiunge latenza per ogni singolo target nel JSON
 *   probeCount    → numero di ping per target (default: PING_PROBES)
 */
@AutoService(Collector.class)
@CollectorMetadata(
    name        = "hardware.internet",
    description = "Latenza Internet, packet loss e stato connessione via ping ICMP + TCP fallback",
    tags        = {"hardware", "network", "realtime"}
)
public class CollectorInternet implements Collector {
    private static final Logger logger = LoggerFactory.getLogger(CollectorInternet.class);

    // With reflective modify those values on core
    private boolean includeAll;   // include latenza per ogni target
    private int     probeCount;   // numero ping per target (0 = usa default)

    // ── Soglie alert ─────────────────────────────────────────────────────────
    private static final long PING_WARNING_MS  = 100L;
    private static final long PING_CRITICAL_MS = 300L;

    // ── Configurazione ping ───────────────────────────────────────────────────
    private static final int  PING_PROBES          = 4;     // ping inviati per target
    private static final int  PING_TIMEOUT_MS       = 3000; // timeout per singolo ping
    private static final int  TCP_CONNECT_TIMEOUT_MS = 3000;
    private static final int  DNS_TIMEOUT_MS         = 3000;

    /**
     * Target di riferimento per il ping.
     * Usiamo server DNS pubblici di vendor diversi per ridondanza e copertura geografica.
     * In caso di blocco ICMP, il TCP fallback usa la porta 443.
     */
    private static final List<PingTarget> TARGETS = Arrays.asList(
        new PingTarget("8.8.8.8",       "Google DNS"),
        new PingTarget("1.1.1.1",       "Cloudflare DNS"),
        new PingTarget("9.9.9.9",       "Quad9 DNS"),
        new PingTarget("208.67.222.222","OpenDNS")
    );

    // Porta usata per il TCP fallback
    private static final int TCP_FALLBACK_PORT = 443;

    // Rilevamento OS (una volta sola)
    private static final boolean IS_WINDOWS =
        System.getProperty("os.name", "").toLowerCase().contains("win");

    @Override
    public String getName() { return "hardware.internet"; }

    @Override
    @SuppressWarnings("unchecked")
    public CollectorResult collect() {
        try {
            int probes = (probeCount > 0) ? probeCount : PING_PROBES;

            JSONObject result     = new JSONObject();
            JSONArray  alertArray = new JSONArray();
            JSONArray  targetArray = new JSONArray();

            List<Long>   successfulPings = new ArrayList<>();
            List<Double> packetLosses    = new ArrayList<>();
            int reachableCount           = 0;

            // ── Misura verso ogni target ──────────────────────────────────────
            for (PingTarget target : TARGETS) {
                PingResult pr = pingTarget(target.ip, probes);

                if (includeAll) {
                    JSONObject t = new JSONObject();
                    t.put("host",         target.ip);
                    t.put("label",        target.label);
                    t.put("method",       pr.method);
                    t.put("reachable",    pr.reachable);
                    t.put("avgPingMs",    pr.reachable ? pr.avgMs : "N/A");
                    t.put("minPingMs",    pr.reachable && pr.minMs >= 0 ? pr.minMs : "N/A");
                    t.put("maxPingMs",    pr.reachable && pr.maxMs >= 0 ? pr.maxMs : "N/A");
                    t.put("packetLossPct", String.format("%.0f", pr.packetLossPct));
                    targetArray.add(t);
                }

                if (pr.reachable) {
                    reachableCount++;
                    successfulPings.add(pr.avgMs);
                    packetLosses.add(pr.packetLossPct);
                } else {
                    packetLosses.add(100.0);
                    alertArray.add(String.format(
                        "[NET] Target %s (%s) non raggiungibile via %s",
                        target.ip, target.label, pr.method));
                }
            }

            boolean internetAvailable = reachableCount > 0;

            // ── Statistiche aggregate ─────────────────────────────────────────
            long avgPingMs = 0L;
            double avgLoss = 0.0;

            if (!successfulPings.isEmpty()) {
                avgPingMs = (long) successfulPings.stream()
                    .mapToLong(Long::longValue).average().orElse(0);
            }
            avgLoss = packetLosses.stream()
                .mapToDouble(Double::doubleValue).average().orElse(100.0);

            result.put("internetAvailable", internetAvailable);
            result.put("pingMs",            internetAvailable ? avgPingMs : "N/A");
            result.put("packetLossPct",     String.format("%.0f", avgLoss));
            result.put("reachableTargets",  reachableCount + "/" + TARGETS.size());

            if (includeAll) {
                result.put("targets", targetArray);
            }

            // ── Alert ─────────────────────────────────────────────────────────

            if (!internetAvailable) {
                alertArray.add(
                    "[NET] Nessuna connessione Internet — tutti i target non raggiungibili");
            } else {
                if (avgPingMs > PING_CRITICAL_MS) {
                    alertArray.add(String.format(
                        "[NET] Latenza critica: %d ms (soglia: %d ms) — connessione molto degradata",
                        avgPingMs, PING_CRITICAL_MS));
                } else if (avgPingMs > PING_WARNING_MS) {
                    alertArray.add(String.format(
                        "[NET] Latenza elevata: %d ms (soglia: %d ms) — possibile congestione di rete",
                        avgPingMs, PING_WARNING_MS));
                }

                if (avgLoss > 0 && avgLoss < 100) {
                    alertArray.add(String.format(
                        "[NET] Packet loss parziale: %.0f%% — instabilità di rete rilevata",
                        avgLoss));
                }

                if (reachableCount < TARGETS.size()) {
                    alertArray.add(String.format(
                        "[NET] %d/%d target non raggiungibili — possibile blocco parziale o routing anomalo",
                        TARGETS.size() - reachableCount, TARGETS.size()));
                }
            }

            result.put("alerts",     alertArray);
            result.put("alertCount", alertArray.size());

            return CollectorResult.ok(getName(), result);

        } catch (Exception e) {
            logger.error("Error occurred while collecting internet connectivity", e);
            JSONObject result = new JSONObject();
            result.put("error", e.getMessage());
            return CollectorResult.failure(getName(), result);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Ping orchestrator — tenta ICMP, poi TCP, poi DNS
    // ════════════════════════════════════════════════════════════════════════

    private PingResult pingTarget(String host, int probes) {
        // Level 1: ICMP ping via ProcessBuilder
        PingResult icmp = icmpPing(host, probes);
        if (icmp.reachable) return icmp;

        logger.debug("ICMP ping failed for {}, trying TCP fallback", host);

        // Level 2: TCP socket ping
        PingResult tcp = tcpPing(host, probes);
        if (tcp.reachable) return tcp;

        logger.debug("TCP ping failed for {}, trying DNS fallback", host);

        // Level 3: DNS resolution
        return dnsFallback(host);
    }

    // ── Level 1: ICMP via OS ping command ────────────────────────────────────

    private PingResult icmpPing(String host, int probes) {
        try {
            List<String> cmd = buildPingCommand(host, probes);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(
                (long) probes * PING_TIMEOUT_MS + 2000, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return PingResult.failed("ICMP");
            }

            return parsePingOutput(output.toString(), probes);

        } catch (Exception e) {
            logger.debug("ICMP ping exception for {}: {}", host, e.getMessage());
            return PingResult.failed("ICMP");
        }
    }

    private List<String> buildPingCommand(String host, int probes) {
        if (IS_WINDOWS) {
            return Arrays.asList(
                "ping",
                "-n", String.valueOf(probes),
                "-w", String.valueOf(PING_TIMEOUT_MS),
                host
            );
        } else {
            // Linux / macOS
            return Arrays.asList(
                "ping",
                "-c", String.valueOf(probes),
                "-W", String.valueOf(PING_TIMEOUT_MS / 1000),
                host
            );
        }
    }

    /**
     * Parsing dell'output del comando ping (Windows e Linux/macOS).
     *
     * Windows example:
     *   Minimum = 12ms, Maximum = 15ms, Average = 13ms
     *   Packets: Sent = 4, Received = 4, Lost = 0 (0% loss)
     *
     * Linux example:
     *   rtt min/avg/max/mdev = 11.432/13.210/15.018/1.234 ms
     *   4 packets transmitted, 4 received, 0% packet loss
     */
    private PingResult parsePingOutput(String output, int probes) {
        try {
            long minMs = -1, avgMs = -1, maxMs = -1;
            double packetLossPct = 100.0;

            if (IS_WINDOWS) {
                // RTT: "Average = Xms"
                for (String line : output.split("\n")) {
                    String l = line.trim();
                    if (l.contains("Average") || l.contains("Moyenne") || l.contains("Media")) {
                        String[] parts = l.split("[=,]");
                        for (String part : parts) {
                            part = part.trim().replaceAll("[^0-9]", "");
                            if (!part.isEmpty()) {
                                avgMs = Long.parseLong(part);
                            }
                        }
                    }
                    if (l.contains("Minimum") || l.contains("Minimo")) {
                        String val = l.replaceAll(".*=\\s*(\\d+)ms.*", "$1").trim();
                        if (val.matches("\\d+")) minMs = Long.parseLong(val);
                    }
                    if (l.contains("Maximum") || l.contains("Massimo")) {
                        String val = l.replaceAll(".*=\\s*(\\d+)ms.*", "$1").trim();
                        if (val.matches("\\d+")) maxMs = Long.parseLong(val);
                    }
                    // Packet loss: "Lost = X (Y% loss)"
                    if (l.contains("Lost") || l.contains("Persi") || l.contains("Perdus")) {
                        String lossVal = l.replaceAll(".*\\((\\d+)%.*", "$1");
                        if (lossVal.matches("\\d+")) packetLossPct = Double.parseDouble(lossVal);
                    }
                }
            } else {
                // Linux/macOS: "rtt min/avg/max/mdev = 11.4/13.2/15.0/1.2 ms"
                for (String line : output.split("\n")) {
                    String l = line.trim();
                    if (l.startsWith("rtt") || l.startsWith("round-trip")) {
                        String[] parts = l.split("=")[1].trim().split("/");
                        if (parts.length >= 3) {
                            minMs = (long) Double.parseDouble(parts[0].trim());
                            avgMs = (long) Double.parseDouble(parts[1].trim());
                            maxMs = (long) Double.parseDouble(parts[2].trim());
                        }
                    }
                    // "4 packets transmitted, 3 received, 25% packet loss"
                    if (l.contains("packet loss")) {
                        String lossVal = l.replaceAll(".*(\\d+)% packet loss.*", "$1");
                        if (lossVal.matches("\\d+")) packetLossPct = Double.parseDouble(lossVal);
                    }
                }
            }

            if (avgMs < 0) return PingResult.failed("ICMP");

            return new PingResult("ICMP", true, avgMs, minMs, maxMs, packetLossPct);

        } catch (Exception e) {
            logger.debug("Ping output parse error: {}", e.getMessage());
            return PingResult.failed("ICMP");
        }
    }

    // ── Level 2: TCP Socket ping ──────────────────────────────────────────────

    private PingResult tcpPing(String host, int probes) {
        List<Long> rtts = new ArrayList<>();

        for (int i = 0; i < probes; i++) {
            long start = System.currentTimeMillis();
            try (Socket socket = new Socket()) {
                socket.connect(
                    new InetSocketAddress(host, TCP_FALLBACK_PORT),
                    TCP_CONNECT_TIMEOUT_MS);
                rtts.add(System.currentTimeMillis() - start);
            } catch (Exception e) {
                // questo probe è perso
            }
        }

        if (rtts.isEmpty()) return PingResult.failed("TCP");

        long avg  = (long) rtts.stream().mapToLong(Long::longValue).average().orElse(0);
        long min  = rtts.stream().mapToLong(Long::longValue).min().orElse(-1);
        long max  = rtts.stream().mapToLong(Long::longValue).max().orElse(-1);
        double loss = (double)(probes - rtts.size()) / probes * 100.0;

        return new PingResult("TCP", true, avg, min, max, loss);
    }

    // ── Level 3: DNS fallback ─────────────────────────────────────────────────

    private PingResult dnsFallback(String host) {
        try {
            long start = System.currentTimeMillis();
            // Timeout DNS tramite ExecutorService
            ExecutorService exec = Executors.newSingleThreadExecutor();
            Future<InetAddress> future = exec.submit(() -> InetAddress.getByName(host));
            exec.shutdown();

            future.get(DNS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            long elapsed = System.currentTimeMillis() - start;

            // DNS risponde = connessione Internet presente, RTT approssimato
            return new PingResult("DNS", true, elapsed, -1, -1, 0.0);

        } catch (Exception e) {
            return PingResult.failed("DNS");
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Inner classes
    // ════════════════════════════════════════════════════════════════════════

    private static class PingTarget {
        final String ip;
        final String label;
        PingTarget(String ip, String label) { this.ip = ip; this.label = label; }
    }

    private static class PingResult {
        final String  method;
        final boolean reachable;
        final long    avgMs;
        final long    minMs;
        final long    maxMs;
        final double  packetLossPct;

        PingResult(String method, boolean reachable, long avgMs,
                   long minMs, long maxMs, double packetLossPct) {
            this.method        = method;
            this.reachable     = reachable;
            this.avgMs         = avgMs;
            this.minMs         = minMs;
            this.maxMs         = maxMs;
            this.packetLossPct = packetLossPct;
        }

        static PingResult failed(String method) {
            return new PingResult(method, false, -1, -1, -1, 100.0);
        }
    }

    @Override
    public Map<String, Class<?>> getAcceptedParameters() {
        return Map.of(
            "includeAll",  Boolean.class,  // latenza per ogni target
            "probeCount",  Integer.class   // numero ping per target
        );
    }

    public static void main(String[] args) {
        CollectorInternet collector = new CollectorInternet();
        collector.includeAll = true;
        collector.probeCount = 4;

        System.out.println("=== Internet Collector Test ===");
        System.out.println("Invio ping in corso...");
        CollectorResult result = collector.collect();
        System.out.println(result.getResult().toJSONString());
    }
}