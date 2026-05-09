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
import oshi.software.os.InternetProtocolStats;
import oshi.software.os.InternetProtocolStats.IPConnection;
import oshi.software.os.OperatingSystem;

/**
 * Network Connections Collector.
 *
 * Analizza le connessioni TCP/UDP attive del sistema e genera alert per
 * comportamenti anomali (troppe connessioni, porte sospette, IP non RFC1918).
 *
 * Logica di alert:
 *
 *   [CONN] Troppe connessioni attive simultanee  → soglia MAX_CONNECTIONS
 *   [PORT] Porta remota nota come sospetta       → lista SUSPICIOUS_PORTS
 *   [PORT] Porta locale esposta insolita         → porta locale < 1024 non comune
 *   [IP]   Connessione verso IP pubblico esterno → IP non in RFC1918 / loopback
 *   [SCAN] Molte connessioni SYN_SENT verso host diversi → possibile port scan uscente
 *   [PROC] Stesso PID con connessioni eccessive  → possibile processo aggressivo
 *
 * Parametri via reflection:
 *   includeAll      → include anche connessioni in stato CLOSED/TIME_WAIT
 *   includePorts    → aggiunge l'analisi delle porte locali in ascolto (LISTEN)
 *   includeAlerts   → abilita la generazione degli alert (default: sempre on)
 */
@AutoService(Collector.class)
@CollectorMetadata(
    name        = "hardware.connections",
    description = "Connessioni TCP/UDP attive, porte aperte e alert di anomalia di rete",
    tags        = {"hardware", "network", "security", "realtime"}
)
public class CollectorConnections implements Collector {
    private static final Logger logger = LoggerFactory.getLogger(CollectorConnections.class);

    // ── Parametri configurabili via reflection ──────────────────────────────
    private boolean includeAll;      // include stati CLOSED, TIME_WAIT, CLOSE_WAIT
    private boolean includePorts;    // include le porte locali in LISTEN
    private boolean includeAlerts;   // abilita alert (default true)

    // ── Soglie ──────────────────────────────────────────────────────────────
    private static final int MAX_CONNECTIONS          = 100;  // alert se superate
    private static final int MAX_CONN_PER_PID         = 30;   // alert se un PID ha troppe conn.
    private static final int MAX_SYN_SENT             = 10;   // alert port scan uscente

    // ── Porte remote note come sospette o associate a C2/malware ────────────
    private static final Set<Integer> SUSPICIOUS_PORTS = new HashSet<>(Arrays.asList(
        // RAT / backdoor classici
        1337, 31337, 4444, 5555, 6666, 7777, 8888, 9999,
        // Mining pool
        3333, 3334, 5556, 5558, 7777, 14444, 14433, 45560,
        // Protocolli legittimi ma spesso abusati
        6667, 6668, 6669,   // IRC (C2 via IRC)
        1080,               // SOCKS proxy
        3128, 8080, 8118,   // proxy aperti
        // Porte di controllo comuni nei tool di attacco
        4899,   // Radmin
        5900,   // VNC (se verso esterno)
        22,     // SSH verso esterno può essere sospetto in certi contesti
        23,     // Telnet
        2222, 2121  // SSH/FTP alternativi
    ));

    // ── Porte locali "insolite" se in ascolto (non standard per un desktop) ─
    private static final Set<Integer> UNUSUAL_LISTEN_PORTS = new HashSet<>(Arrays.asList(
        4444, 5555, 6666, 7777, 8888, 9999, 1337, 31337,
        3333, 3334, 1080, 4899
    ));

    // ── Stati da escludere se includeAll = false ─────────────────────────────
    private static final Set<String> NOISY_STATES = new HashSet<>(Arrays.asList(
        "CLOSED", "TIME_WAIT", "CLOSE_WAIT"
    ));

    @Override
    public String getName() { return "hardware.connections"; }

    @Override
    @SuppressWarnings("unchecked")
    public CollectorResult collect() {
        try {
            SystemInfo            si       = new SystemInfo();
            OperatingSystem       os       = si.getOperatingSystem();
            InternetProtocolStats ipStats  = os.getInternetProtocolStats();

            JSONArray  connArray  = new JSONArray();
            JSONArray  portArray  = new JSONArray();
            JSONArray  alertArray = new JSONArray();

            // ── Recupero connessioni TCP + UDP ────────────────────────────────
            List<IPConnection> allConns = new ArrayList<>();
            allConns.addAll(ipStats.getConnections());

            // ── Filtro stati ─────────────────────────────────────────────────
            List<IPConnection> filtered = allConns.stream()
                .filter(c -> includeAll || !NOISY_STATES.contains(normalizeState(c.getState().name())))
                .collect(Collectors.toList());

            // ── Contatori per alert ──────────────────────────────────────────
            int             synSentCount   = 0;
            Map<Integer, Integer> pidCount = new HashMap<>();  // PID → n. connessioni
            Set<String>     externalIps    = new LinkedHashSet<>();
            Set<Integer>    suspPortsFound = new LinkedHashSet<>();
            Set<Integer>    listenPorts    = new LinkedHashSet<>();

            // ── Costruzione JSON connessioni ─────────────────────────────────
            for (IPConnection conn : filtered) {
                String localAddr  = formatAddress(conn.getLocalAddress(),  conn.getLocalPort());
                String remoteAddr = formatAddress(conn.getForeignAddress(), conn.getForeignPort());
                String state      = normalizeState(conn.getState().name());
                int    pid        = conn.getowningProcessId();
                int    localPort  = conn.getLocalPort();
                int    remotePort = conn.getForeignPort();
                String type       = conn.getType();  // TCP4, TCP6, UDP4, UDP6

                JSONObject obj = new JSONObject();
                obj.put("type",       type);
                obj.put("localAddr",  localAddr);
                obj.put("remoteAddr", remoteAddr);
                obj.put("state",      state);
                obj.put("pid",        pid > 0 ? pid : "N/A");

                connArray.add(obj);

                // ── Raccolta dati per alert ───────────────────────────────────

                // Porta locale in LISTEN
                if ("LISTEN".equals(state)) {
                    listenPorts.add(localPort);
                }

                // SYN_SENT counter (possibile port scan)
                if ("SYN_SENT".equals(state)) synSentCount++;

                // Connessioni per PID
                if (pid > 0) {
                    pidCount.merge(pid, 1, Integer::sum);
                }

                // IP remoto esterno (non RFC1918, non loopback, non vuoto)
                String remoteIp = bytesToIp(conn.getForeignAddress());
                if (!remoteIp.isEmpty() && isPublicIp(remoteIp) && remotePort > 0) {
                    externalIps.add(remoteIp);
                }

                // Porta remota sospetta
                if (remotePort > 0 && SUSPICIOUS_PORTS.contains(remotePort)) {
                    suspPortsFound.add(remotePort);
                }
            }

            // ── Sezione porte in ascolto (se includePorts = true) ────────────
            if (includePorts) {
                for (int port : listenPorts) {
                    JSONObject p = new JSONObject();
                    p.put("port",     port);
                    p.put("unusual",  UNUSUAL_LISTEN_PORTS.contains(port));
                    portArray.add(p);
                }
            }

            // ════════════════════════════════════════════════════════════════
            // GENERAZIONE ALERT
            // ════════════════════════════════════════════════════════════════
            if (includeAlerts) {

                // [CONN] Troppe connessioni attive
                if (filtered.size() > MAX_CONNECTIONS) {
                    alertArray.add(String.format(
                        "[CONN] Numero connessioni attive anomalo: %d (soglia: %d) — possibile stress di rete o malware",
                        filtered.size(), MAX_CONNECTIONS));
                }

                // [PORT] Porte remote sospette
                for (int port : suspPortsFound) {
                    alertArray.add(String.format(
                        "[PORT] Connessione attiva verso porta remota sospetta: %d — comune in backdoor, C2 o mining pool",
                        port));
                }

                // [PORT] Porte locali insolite in ascolto
                for (int port : listenPorts) {
                    if (UNUSUAL_LISTEN_PORTS.contains(port)) {
                        alertArray.add(String.format(
                            "[PORT] Porta locale insolita in ascolto: %d — potenziale backdoor o RAT in attesa di connessione",
                            port));
                    }
                }

                // [IP] Connessioni verso IP pubblici esterni
                if (!externalIps.isEmpty()) {
                    List<String> sample = new ArrayList<>(externalIps);
                    int shown = Math.min(sample.size(), 5);
                    alertArray.add(String.format(
                        "[IP] %d connessione/i verso IP pubblici esterni. Esempi: %s",
                        externalIps.size(),
                        String.join(", ", sample.subList(0, shown))
                            + (sample.size() > 5 ? " ..." : "")));
                }

                // [SCAN] Possibile port scan uscente
                if (synSentCount > MAX_SYN_SENT) {
                    alertArray.add(String.format(
                        "[SCAN] %d connessioni in stato SYN_SENT: possibile port scan uscente o malware che cerca host aperti",
                        synSentCount));
                }

                // [PROC] PID con troppo connessioni
                pidCount.forEach((pid, count) -> {
                    if (count > MAX_CONN_PER_PID) {
                        alertArray.add(String.format(
                            "[PROC] PID %d ha %d connessioni attive — comportamento aggressivo, possibile malware o scraper",
                            pid, count));
                    }
                });
            }

            // ── Output finale ────────────────────────────────────────────────
            JSONObject result = new JSONObject();
            result.put("connectionCount", filtered.size());
            result.put("connections",     connArray);
            result.put("alertCount",      alertArray.size());
            result.put("alerts",          alertArray);

            if (includePorts) {
                result.put("listenPortCount", portArray.size());
                result.put("listenPorts",     portArray);
            }

            return CollectorResult.ok(getName(), result);

        } catch (Exception e) {
            logger.error("Error occurred while collecting network connections", e);
            JSONObject result = new JSONObject();
            result.put("error", e.getMessage());
            return CollectorResult.failure(getName(), result);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Converte un array di byte IP (4 o 16 byte) in stringa "a.b.c.d" o "::".
     * Restituisce stringa vuota se l'array è null o tutti zero.
     */
    private String bytesToIp(byte[] addr) {
        if (addr == null || addr.length == 0) return "";
        boolean allZero = true;
        for (byte b : addr) { if (b != 0) { allZero = false; break; } }
        if (allZero) return "";

        if (addr.length == 4) {
            return String.format("%d.%d.%d.%d",
                addr[0] & 0xFF, addr[1] & 0xFF, addr[2] & 0xFF, addr[3] & 0xFF);
        }
        // IPv6 compresso
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < addr.length; i += 2) {
            if (i > 0) sb.append(":");
            sb.append(String.format("%x", ((addr[i] & 0xFF) << 8) | (addr[i + 1] & 0xFF)));
        }
        return sb.toString();
    }

    /**
     * Formatta indirizzo + porta come "ip:porta".
     * Restituisce "*:porta" se l'IP è assente (LISTEN su tutte le interfacce).
     */
    private String formatAddress(byte[] addr, int port) {
        String ip = bytesToIp(addr);
        String host = ip.isEmpty() ? "*" : ip;
        return port > 0 ? host + ":" + port : host;
    }

    /**
     * Determina se un IP è pubblico (non RFC1918, non loopback, non link-local).
     * Considera solo IPv4 per semplicità.
     */
    private boolean isPublicIp(String ip) {
        if (ip.isEmpty()) return false;
        if (ip.startsWith("127."))  return false;  // loopback
        if (ip.startsWith("10."))   return false;  // RFC1918 /8
        if (ip.startsWith("169.254.")) return false; // link-local
        if (ip.startsWith("::1"))   return false;  // IPv6 loopback
        if (ip.startsWith("fe80:")) return false;  // IPv6 link-local
        // RFC1918: 172.16.0.0/12
        if (ip.startsWith("172.")) {
            try {
                int second = Integer.parseInt(ip.split("\\.")[1]);
                if (second >= 16 && second <= 31) return false;
            } catch (NumberFormatException ignored) {}
        }
        // RFC1918: 192.168.0.0/16
        if (ip.startsWith("192.168.")) return false;
        return true;
    }

    /**
     * Normalizza il nome dello stato OSHI in un formato leggibile.
     * OSHI restituisce valori come "ESTABLISHED", "LISTEN", "SYN_SENT", ecc.
     */
    private String normalizeState(String raw) {
        if (raw == null) return "UNKNOWN";
        return raw.replace("_", " ").toUpperCase();
    }

    @Override
    public Map<String, Class<?>> getAcceptedParameters() {
        return Map.of(
            "includeAll",    Boolean.class,  // include stati CLOSED/TIME_WAIT
            "includePorts",  Boolean.class,  // include lista porte in LISTEN
            "includeAlerts", Boolean.class   // abilita alert (default: on)
        );
    }

    public static void main(String[] args) {
        CollectorConnections collector = new CollectorConnections();
        collector.includeAll    = false;
        collector.includePorts  = true;
        collector.includeAlerts = true;

        System.out.println("=== Network Connections Collector Test ===");
        CollectorResult result = collector.collect();
        System.out.println(result.getResult().toJSONString());
    }
}