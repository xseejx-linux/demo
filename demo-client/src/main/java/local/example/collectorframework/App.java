package local.example.collectorframework;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import io.github.xseejx.collectorframework.engine.ServiceManager;
import io.github.xseejx.collectorframework.engine.TaskManager;
import io.github.xseejx.collectorframework.engine.TaskModel;
import local.example.collectorframework.connectors.Connector;




/**
 * 
 *
 */
public class App 
{
    // Init Thread for Runnable class Connector (Main Connector Thread)
    // Executes a first service with a collector which will return arguments to pass to the server. (Only if server is avaible)
    public static void main( String[] args )
    {
        JSONObject json = CollectorMetadataJsonExporter.gatherAllMetadataAsJson();
        System.out.println(json.toJSONString());
        if(true)
            return;
        Connector connector = new Connector("MY_COMPUTER");;

        /**
         * Initialize connector to talk with server
         */
        
        JSONObject hello = new JSONObject();
        hello.put("type", "hello");
        hello.put("message", connector.computerId);
        // Sends Hello request to server with computerID
        //System.out.println("[Connector] Hello sent for ID: " + connector.computerId);

        JSONObject instruction  = connector.POST(hello, "/api/hello");
        if(instruction.get("type")==null){
            System.err.println("[!] No server Online found");
            return;
        }

        System.out.println("[+] Received Message: " + instruction);




        TaskManager manager = new TaskManager();
        ServiceManager service = new ServiceManager();
        boolean exit = false;
        JSONObject jsonBuilder = new JSONObject();

        /*
        This next step is very important, we are going to pass to the server all of our collectors
        But most important, we are going to pass the collector metadata
        So we are managing creation of the collectors just from the server. (from the frontend)
         */





            //TODO: Startign here:
            while (!exit) {
                jsonBuilder.clear();
                jsonBuilder.put("type", "computer_id");
                jsonBuilder.put("message", connector.computerId);

                // Get instructions 
                JSONObject jsonMessage = connector.GET(jsonBuilder, "/api/get_instruction");
                System.out.println(jsonMessage.toJSONString());
                // Do action
                exit = true;
            }

            service.end();
            manager.shutdown();
            System.out.println("[Connector] Communication Ended");






        

        //NEXT OPERATION SEQUENTIALLY
        //sends result to server -> send()
        //Waits confirm
        //ENTERS WHILE LOOP
        //Wait + recive instructions from server -> waitResponse()
        //switch for applying operations

        /*
        The operations (basics):
            1: create a task -> of collector, of dispatcher     <- returns taskID 
            2: destroy a task -> of taskID <- returns status of request (e.g true/false) 
            3: Stop everything //exits from loop, and stop everything
        */
        
        

        /*String task = tasks.createTask(
            new TaskModel(
                "generic.test",
                Map.of("value1", true),
                "* * * * * ?",
                "system"    // Group
                "rabbidmq" // dispatcher selection
            )
        );
        System.out.println("TASK CREATED: " + taskId);*/




        
        
        





        

        /*String task = manager.createTask(new TaskModel(
            "generic.test",
            Map.of("value1", true, "value2", "Second"),
            "* * * * * ?",
            "system",
            "rabbitmq"
        ));
        String task2 = manager.createTask(new TaskModel(
            "generic.test",
            Map.of("value1", false, "value2", "First"),
            "* * * * * ?",
            "system",
            "rabbitmq"
        ));*/

        

        //System.out.println("Deleting task: " + task);
        //boolean deleted = manager.deleteTask(task, "system");
        //System.out.println("Delete result: " + deleted);



            
    }


    /*public static JSONObject gatherAllMetadataAsJson() {
        ServiceManager service = new ServiceManager();
        List<String> collectors = service.listAvailable();

        JSONObject result = new JSONObject();
        result.put("type", "metadata_result");

        JSONArray messageArray = new JSONArray();

        for (String collectorName : collectors) {
            JSONObject collectorData = new JSONObject();
            collectorData.put("collector", collectorName);

            List<String> metadataLines = service.getMetadata(collectorName);
            JSONArray metadataArray = new JSONArray();
            // org.json.simple.JSONArray.addAll expects a Collection
            metadataArray.addAll(metadataLines);

            collectorData.put("metadata", metadataArray);
            messageArray.add(collectorData);
        }

        result.put("messagge", messageArray); // match the required key spelling
        return result;
    }*/


}
