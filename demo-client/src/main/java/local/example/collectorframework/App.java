package local.example.collectorframework;

import java.io.IOException;
import java.util.Map;

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
        Connector connector = new Connector("Test");;

        
        /**
         * Initialize connector to talk with server
         */
        
            JSONObject hello = new JSONObject();
            hello.put("type", "hello");
            hello.put("message", connector.computerId);
            // Sends Hello request to server with computerID
            System.out.println("[Connector] Hello sent for ID: " + connector.computerId);

            JSONObject instruction  = connector.POST(hello, "/api/hello");
            System.out.println("[Connector] Received Message: " + instruction);
            System.out.println("[Connector] Beginning of Communication");

        

        TaskManager manager = new TaskManager();
        ServiceManager service = new ServiceManager();
        int codeAction = 0;
        JSONObject jsonBuilder = new JSONObject();

        
            //TODO: Startign here:
            while (codeAction != 3) {
                jsonBuilder.clear();
                jsonBuilder.put("type", "computer_id");
                jsonBuilder.put("message", connector.computerId);

                // Get instructions 
                JSONObject jsonMessage = connector.GET(jsonBuilder, "/api/get_instruction");
                System.out.println(jsonMessage.toJSONString());
                // Do action
                codeAction = 3;
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
}
