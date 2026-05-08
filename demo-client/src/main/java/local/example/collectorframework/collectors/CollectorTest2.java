package local.example.collectorframework.collectors;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.auto.service.AutoService;





import io.github.xseejx.collectorframework.api.Collector;
import io.github.xseejx.collectorframework.api.CollectorMetadata;
import io.github.xseejx.collectorframework.api.CollectorResult;
import io.github.xseejx.collectorframework.api.CollectorMetadata.ParameterType;

import org.json.simple.JSONObject;

@AutoService(Collector.class)
@CollectorMetadata(
    name        = "generic.test2",
    description = "A simple test collector2.0",
    tags        = {"generic", "test2"},
    parameters  = {
        @CollectorMetadata.CollectorParameter(
            key = "recursive",
            type = ParameterType.BOOLEAN,
            defaultValue = "true"
        ),
        @CollectorMetadata.CollectorParameter(
            key = "path",
            type = ParameterType.PATH,
            required = true
        )
    }
)

public class CollectorTest2 implements Collector{
    private static final Logger logger = LoggerFactory.getLogger(CollectorTest.class);


    // With reflective modify those values on core
    private boolean value1 = false;
    private String value2 = "Test0";

    @Override
    public String getName() { return "generic.test"; }

    @SuppressWarnings("unchecked")
    @Override
    public CollectorResult collect() { 
        try {
           
            


            JSONObject result = new JSONObject();
            result.put("Value1", value1);
            result.put("Value2", value2);
           
            //Thread.sleep(2000); // Simulate some delay
            return CollectorResult.ok(getName(), result);


        } catch (Exception e) {
            logger.error("Error occurred while collecting Test information", e);
            JSONObject result = new JSONObject();
            return CollectorResult.failure(getName(), result);
        }
    }

    @Override
    public Map<String, Class<?>> getAcceptedParameters() {
        return Map.of(
            "value1",  Boolean.class,
            "value2",  String.class
        );
    }





    @SuppressWarnings("unused")
    public static void main( String[] args )
    {   
        
        JSONObject result = new JSONObject();
        logger.info("Test Hello");
    }
}