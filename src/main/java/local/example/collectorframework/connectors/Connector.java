package local.example.collectorframework.connectors;

public class Connector implements Runnable{

    /*
        0. Runnable class 
        1. must know Informations about server, IP, PORT. (passed via arguments)
        2. Creates a TCP-socket to the server IP:PORT 
        3. Sends an hello to server (With computer-ID passed via arguments)
        4. Waits for other messages instruction from server
        5. Parses communication in json, and send it back to App.java
    */


    @Override
    public void run() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'run'");
    }
}
