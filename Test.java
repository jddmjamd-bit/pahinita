import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.gson.*; public class Test {
    private static final Logger logger = LoggerFactory.getLogger(Test.class);
 public static void main(String[] args) { JsonObject o = new JsonObject(); o.addProperty("test", "hello"); logger.info(new GsonBuilder().create().toJson(o, Object.class)); } }
