package local.example.collectorframework;

import java.util.Map;

import io.github.xseejx.collectorframework.engine.ServiceManager;
import io.github.xseejx.collectorframework.engine.TaskManager;
import io.github.xseejx.collectorframework.engine.TaskModel;




/**
 * Hello world!
 *
 */
public class App 
{
    public static void main( String[] args )
    {
        /*// ── Normal service execution ─────────────────────────────
        ServiceManager service = new ServiceManager();

        String result = service.activateServiceSync(
            "generic.test",
            Map.of()
        );

        System.out.println("SERVICE RESULT: " + result);

        CollectorEngine engine = new CollectorEngine(new CollectorRegistry());*/
        // ── Task scheduling test ────────────────────────────────
        //TaskManager tasks = new TaskManager();

        /*String taskId = tasks.createTask(
            new TaskModel(
                "generic.test",
                Map.of("value1", true),
                "* * * * * ?",
                "terminal" // dispatcher selection
            )
        );
        System.out.println("TASK CREATED: " + taskId);*/

        /*try {
            //Thread.sleep(15000); // let scheduler run
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }**/

        //tasks.shutdown();
        //service.end();


        TaskManager manager = new TaskManager();

        //System.out.println("Creating two scheduled tasks, one using ConsoleDispatcher and one using RabbitMQ dispatcher...");

        /*String task1 = manager.createTask(new TaskModel(
            "generic.test",
            Map.of("value1", true, "value2", "Console run"),
            "system",
            "console"
        ));*/
        String task1 = "<null>";
        //System.out.println(manager.listAvailable());
        String task2 = manager.createTask(new TaskModel(
            "generic.test",
            Map.of("value1", true, "value2", "NC run"),
            "* * * * * ?",
            "system",
            "rabbitmq"
        ));
        System.out.println("Created tasks: " + task1 + ", " + task2);
        System.out.println("Waiting for scheduled executions...");

        try {
            while (true) {
                
                Thread.sleep(12000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.println("Deleting task: " + task2);
        boolean deleted = manager.deleteTask(task2, "system");
        System.out.println("Delete result: " + deleted);

        manager.shutdown();
        System.out.println("Scheduler demo complete.");



    }
}
