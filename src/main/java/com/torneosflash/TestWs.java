import io.javalin.Javalin;
public class TestWs {
    public static void main(String[] args) {
        Javalin app = Javalin.create(config -> {
            config.jetty.modifyWebSocketServletFactory(wsFactory -> {
                wsFactory.setMaxTextMessageSize(20000000);
            });
        });
    }
}
