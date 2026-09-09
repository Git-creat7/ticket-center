package asia.creat.client;

import asia.creat.config.InternalApiConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = OrderClientContractTest.ClientConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "ticket.internal-token=contract-token")
class OrderClientContractTest {

    private static final HttpServer SERVER = server();

    @Autowired
    private OrderClient client;

    @Configuration
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @EnableFeignClients(clients = OrderClient.class)
    @Import(InternalApiConfig.class)
    static class ClientConfig {
    }

    private static HttpServer server() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/", exchange -> {
                boolean authorized = "contract-token".equals(exchange.getRequestHeaders().getFirst("X-Ticket-Internal-Token"));
                String path = exchange.getRequestURI().getPath();
                String response = path.equals("/internal/credits/42") ? "120"
                        : "[{\"id\":1,\"stock\":0,\"hasWaitlist\":true,\"beginTime\":\"2030-01-01 12:00:00\"}]";
                byte[] body = response.getBytes(StandardCharsets.UTF_8);
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
        registry.add("ticket.clients.order-url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @Test
    void feignSendsCredentialAndDeserializesTicketView() {
        var tickets = client.queryTickets(1L);
        assertEquals(1, tickets.size());
        assertEquals(0, tickets.get(0).getStock());
        assertTrue(tickets.get(0).getHasWaitlist());
        assertEquals(LocalDateTime.of(2030, 1, 1, 12, 0), tickets.get(0).getBeginTime());
    }

    @Test
    void feignReadsBalanceWithoutLoadingUserEntity() {
        assertEquals(120, client.getCredits(42L));
    }
}
