package org.evochora.node.processes.http.api.node;

import com.typesafe.config.ConfigFactory;
import io.javalin.Javalin;
import io.javalin.http.Handler;
import org.evochora.node.spi.ServiceRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.mockito.Mockito.*;

public class NodeControllerTest {

    @Test
    public void testRegisterRoutes() {
        Javalin app = mock(Javalin.class);
        NodeController controller = new NodeController(new ServiceRegistry(), ConfigFactory.empty());

        controller.registerRoutes(app, "/node");

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Handler> handlerCaptor = ArgumentCaptor.forClass(Handler.class);
        verify(app).post(pathCaptor.capture(), handlerCaptor.capture());

        // We can't easily test the System.exit call, so we'll just verify the route is registered.
        assert(pathCaptor.getValue().equals("/node/stop"));
    }
}