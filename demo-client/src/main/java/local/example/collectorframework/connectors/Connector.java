package local.example.collectorframework.connectors;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.*;
import java.net.Socket;

public class Connector implements Runnable {

    private final static String SERVER_IP = "localhost";
    private final static int SERVER_PORT = 8979;

    private final String computerId;
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private volatile boolean running = true;

    /**
     * Constructor that accepts the computer ID (passed via arguments).
     *
     * @param computerId Unique identifier of this computer/node.
     */
    public Connector(String computerId) {
        this.computerId = computerId;
    }

    /**
     * Sends a JSON message to the server.
     *
     * @param jsonMessage A JSON string to send.
     * @throws IOException if an I/O error occurs.
     */
    public void send(String jsonMessage) throws IOException {
        if (out == null) {
            throw new IOException("Output stream not initialized. Connection may not be established.");
        }
        out.println(jsonMessage);
        out.flush();
    }

    /**
     * Waits for and reads the next JSON response from the server.
     *
     * @return JSONObject representing the server's message.
     * @throws IOException if reading fails or stream is closed.
     * @throws ParseException 
     * @throws org.json.JSONException if the received data is not valid JSON.
     */
    public JSONObject waitResponse() throws IOException, ParseException  {
        String jsonLine = in.readLine();
        if (jsonLine == null) {
            throw new IOException("Connection closed by server.");
        }
        JSONParser parser = new JSONParser();
        return (JSONObject) parser.parse(jsonLine);
    }

    @Override
    public void run() {
        try {
            // 1. Create TCP socket to server
            socket = new Socket(SERVER_IP, SERVER_PORT);
            out = new PrintWriter(socket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            // 2. Send hello message with computer ID
            JSONObject hello = new JSONObject();
            hello.put("type", "hello");
            hello.put("computerId", computerId);
            send(hello.toString());
            System.out.println("[Connector] Hello sent for ID: " + computerId);

            // 3. Continuously wait for and process server instructions
            while (running) {
                JSONObject instruction = waitResponse();
                System.out.println("[Connector] Received instruction: " + instruction);

                // Example: respond to a "ping" message or any other custom handling
                if (instruction.containsKey("command")) {
                    String cmd = (String) instruction.get("command");
                    if ("shutdown".equalsIgnoreCase(cmd)) {
                        System.out.println("[Connector] Shutdown command received. Stopping.");
                        break;
                    }
                    // Add more command handling as needed
                }
            }
        } catch (Exception e) {
            System.err.println("[Connector] Error in communication: " + e.getMessage());
        } finally {
            stop();
        }
    }

    /**
     * Gracefully stops the connector and closes all resources.
     */
    public void stop() {
        running = false;
        try {
            if (in != null) in.close();
            if (out != null) out.close();
            if (socket != null) socket.close();
        } catch (IOException e) {
            System.err.println("[Connector] Error closing resources: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------------
    // Example usage (can be removed in production)
    // ------------------------------------------------------------------------
    public static void main(String[] args) {
        /*if (args.length < 1) {
            System.out.println("Usage: java Connector <computer-id>");
            return;
        }*/
        Connector connector = new Connector("Test");
        new Thread(connector).start();
        System.out.println("Stopped");
    }
}