package local.example.collectorframework;

import java.io.IOException;
import java.util.HashMap;
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
        JSONObject jsonMetadata = CollectorMetadataJsonExporter.gatherAllMetadataAsJson();

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

        JSONObject metaMessage =  connector.POST(jsonMetadata, "/api/metadata");
        //System.out.println("MetadataMessage: "+metaMessage.toJSONString());



        //TODO: Startign here:
        while (!exit) {
            jsonBuilder.clear();
            jsonBuilder.put("type", "computer_id");
            jsonBuilder.put("message", connector.computerId);
        
            JSONObject jsonMessage = connector.GET(jsonBuilder, "/api/get_instruction");
            System.out.println(jsonMessage.toJSONString());
        
            if (jsonMessage != null && jsonMessage.containsKey("type")) {
                String type = String.valueOf(jsonMessage.get("type"));
        
                if ("instruction_add_task".equals(type)) {
                    JSONArray tasksArray = (JSONArray) jsonMessage.get("message");
        
                    if (tasksArray != null) {
                        for (int i = 0; i < tasksArray.size(); i++) {
                            JSONObject taskDef = (JSONObject) tasksArray.get(i);
        
                            String name = String.valueOf(taskDef.get("name"));
                            JSONArray params = (JSONArray) taskDef.get("parameters");
        
                            Map<String, Object> paramMap = new HashMap<>();
                            if (params != null) {
                                for (int j = 0; j < params.size(); j++) {
                                    JSONObject p = (JSONObject) params.get(j);
                                    String key = String.valueOf(p.get("key"));
                                    Object value = p.get("value");
                                    paramMap.put(key, value);
                                }
                            }
        
                            String cron = taskDef.get("cron-value") != null
                                    ? String.valueOf(taskDef.get("cron-value"))
                                    : "*/5 * * * *";
        
                            String group = taskDef.get("group") != null
                                    ? String.valueOf(taskDef.get("group"))
                                    : "default";
        
                            String dispatcher = taskDef.get("dispatcher") != null
                                    ? String.valueOf(taskDef.get("dispatcher"))
                                    : "rabbitmq";
        
                            String taskId = manager.createTask(
                                    new TaskModel(name, paramMap, cron, group, dispatcher)
                            );
        
                            JSONObject report = new JSONObject();
                            report.put("task_id", taskId);
                            report.put("computer_id", connector.computerId);
        
                            connector.POST(report, "/api/task_created");
                        }
                    }
        
                } else if ("instruction_del_task".equals(type)) {
                    String taskId = String.valueOf(jsonMessage.get("message"));
                    boolean deleted = manager.deleteTask(taskId, "system");
        
                    if (!deleted) {
                        System.err.println("Failed to delete task: " + taskId);
                    }
        
                } else if ("instruction".equals(type)) {
                    String msg = String.valueOf(jsonMessage.get("message"));
        
                    if ("STOP_MACHINE".equals(msg)) {
                        exit = true;
                        System.out.println("Received STOP_MACHINE. Exiting.");
                    }
                }
            }
            // Small sleep to prevent tight polling when no instruction
            //Thread.sleep(500);
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
        
        

        /*String task = manager.createTask(
            new TaskModel(
                "generic.test",
                Map.of("value1", true),
                "* * * * * ?",
                "system",   // Group
                "rabbitmq" // dispatcher selection
            )
        );
        System.out.println("TASK CREATED: " + task);*/




        
        
        





        

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
