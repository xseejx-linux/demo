package local.example.collectorframework;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import com.google.auto.service.AutoService;

import io.github.xseejx.collectorframework.api.DispatcherMetadata;
import io.github.xseejx.collectorframework.api.CollectorResult;
import io.github.xseejx.collectorframework.api.ResultDispatcher;

@AutoService(ResultDispatcher.class)
@DispatcherMetadata(name = "ncdispatcher", description = "NC dispatcher")
public class NcDispatcher implements ResultDispatcher {

    private final String host="127.0.0.1";
    private final int port=8080;


    public NcDispatcher() {
        //this.host = host;
        //this.port = port;
    }

    @Override
    public void dispatch(String taskId, String groupName, CollectorResult result) {
        try (Socket socket = new Socket("127.0.0.1", 8080);
             OutputStream out = socket.getOutputStream()) {

            String payload =
                "{"
                    + "\"taskId\":\"" + taskId + "\","
                    + "\"group\":\"" + groupName + "\","
                    + "\"data\":" + result.getResult().toJSONString()
                + "}";

            out.write(payload.getBytes(StandardCharsets.UTF_8));
            out.write('\n');
            out.flush();

        } catch (Exception e) {
            System.err.println("[NcDispatcher] Failed: " + e.getMessage());
        }
    }
}
