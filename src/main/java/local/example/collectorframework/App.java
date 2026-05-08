package local.example.collectorframework;

import java.util.Map;

import io.github.xseejx.collectorframework.engine.ServiceManager;
import io.github.xseejx.collectorframework.engine.TaskManager;
import io.github.xseejx.collectorframework.engine.TaskModel;




/**
 * 
 *
 */
public class App 
{
    // Init Thread for Runnable class Connector (Main Connector Thread)
    // Executes a first service with a collector which will return arguments to pass to the server. (Only if server is avaible)
    //
    public static void main( String[] args )
    {
        TaskManager manager = new TaskManager();
        ServiceManager service = new ServiceManager();

        //waitResponse() (IT STOPS MAIN)
        //FIRST EXECUTION AFTER CONNECTOR ESTABLISHED A CONNECTION WITH A SERVER (uses waitResponse() IT STOPS MAIN)
        
        String result = service.activateServiceSync(
            "generic.test", //TODO: Make collector for retrive infos about host
            Map.of()
        );
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










        

        String task = manager.createTask(new TaskModel(
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
        ));

        

        //System.out.println("Deleting task: " + task);
        //boolean deleted = manager.deleteTask(task, "system");
        //System.out.println("Delete result: " + deleted);
        try {
            while (true) {
                
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }


        service.end();
        manager.shutdown();    
    }
}
