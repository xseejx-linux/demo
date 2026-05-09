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
import oshi.hardware.NetworkIF;

@AutoService(Collector.class)
@CollectorMetadata(
    name = "system.network",
    description = "Network interfaces and realtime traffic",
    tags = {"system", "network", "realtime"}
)
public class CollectorNetwork implements Collector {

    private static final Logger logger = LoggerFactory.getLogger(CollectorNetwork.class);

    private boolean includeAll;

    @Override
    public String getName() {
        return "system.network";
    }

    @Override
    @SuppressWarnings("unchecked")
    public CollectorResult collect() {
        try {
            SystemInfo si = new SystemInfo();
            List<NetworkIF> interfaces = si.getHardware().getNetworkIFs();

            JSONObject result = new JSONObject();
            JSONArray netArray = new JSONArray();

            if (includeAll) {
                // snapshot iniziale
                for (NetworkIF net : interfaces) {
                    net.updateAttributes();
                }

                // salva valori iniziali
                long[] recvBefore = new long[interfaces.size()];
                long[] sentBefore = new long[interfaces.size()];

                for (int i = 0; i < interfaces.size(); i++) {
                    recvBefore[i] = interfaces.get(i).getBytesRecv();
                    sentBefore[i] = interfaces.get(i).getBytesSent();
                }

                // aspetta 1 secondo
                Thread.sleep(1000);

                // seconda lettura + calcolo traffico
                for (int i = 0; i < interfaces.size(); i++) {

                    NetworkIF net = interfaces.get(i);
                    net.updateAttributes();

                    long downloadBps = net.getBytesRecv() - recvBefore[i];
                    long uploadBps = net.getBytesSent() - sentBefore[i];

                    JSONObject n = new JSONObject();

                    n.put("name", net.getName());
                    n.put("displayName", net.getDisplayName());
                    n.put("mac", net.getMacaddr());

                    n.put("ipv4", String.join(", ", net.getIPv4addr()));
                    n.put("ipv6", String.join(", ", net.getIPv6addr()));

                    n.put("mtu", net.getMTU());
                    n.put("speed", net.getSpeed());

                    n.put("bytesReceived", net.getBytesRecv());
                    n.put("bytesSent", net.getBytesSent());

                    double downKB = downloadBps / 1024.0;
                    double upKB = uploadBps / 1024.0;

                    n.put("downloadKBs", String.format("%.2f", downKB));
                    n.put("uploadKBs", String.format("%.2f", upKB));

                    n.put("isUp", net.getIfOperStatus().toString());

                    netArray.add(n);
                
                }

                result.put("interfaceCount", interfaces.size());
                result.put("interfaces", netArray);
            }

            return CollectorResult.ok(getName(), result);

        } catch (Exception e) {
            logger.error("Error collecting network data", e);
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
        CollectorNetwork c = new CollectorNetwork();
        c.includeAll = true;

        CollectorResult res = c.collect();

        System.out.println("=== NETWORK CLEAN INFO ===");
        System.out.println(res.getResult().toJSONString());
    }
}