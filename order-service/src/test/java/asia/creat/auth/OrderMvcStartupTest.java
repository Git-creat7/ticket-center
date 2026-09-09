package asia.creat.auth;

import asia.creat.client.CoreClient;
import asia.creat.config.InternalApiConfig;
import asia.creat.config.OrderMvcConfigurer;
import asia.creat.dto.UserDTO;
import asia.creat.utils.RequireAdmin;
import asia.creat.utils.UserHolder;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = OrderMvcStartupTest.TestConfig.class,
        properties = "ticket.internal-token=contract-token")
@AutoConfigureMockMvc
class OrderMvcStartupTest {

    private static final HttpServer CORE = server();

    @Autowired
    private MockMvc mockMvc;

    @Configuration
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @EnableFeignClients(clients = CoreClient.class)
    @Import({OrderMvcConfigurer.class, InternalApiConfig.class, AdminController.class})
    static class TestConfig {
    }

    @RestController
    static class AdminController {
        @RequireAdmin
        @GetMapping("/test/admin")
        public String admin() {
            return "ok";
        }
    }

    private static HttpServer server() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/users/", exchange -> {
                boolean authorized = "contract-token".equals(exchange.getRequestHeaders().getFirst("X-Ticket-Internal-Token"));
                byte[] body = Boolean.toString(exchange.getRequestURI().getPath().equals("/internal/users/1/admin"))
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(authorized ? 200 : 403, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("ticket.clients.core-url", () -> "http://127.0.0.1:" + CORE.getAddress().getPort());
    }

    @AfterAll
    static void stopServer() {
        CORE.stop(0);
    }

    @AfterEach
    void clearUser() {
        UserHolder.removeUser();
    }

    @Test
    void mvcStartsWithRealFeignClientAndChecksCurrentRole() throws Exception {
        UserDTO admin = new UserDTO();
        admin.setId(1L);
        UserHolder.saveUser(admin);
        mockMvc.perform(get("/test/admin")).andExpect(status().isOk());

        UserDTO user = new UserDTO();
        user.setId(2L);
        UserHolder.saveUser(user);
        mockMvc.perform(get("/test/admin")).andExpect(status().isForbidden());
    }
}
