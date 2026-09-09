package asia.creat.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0")
class GatewayRoutesTest {

    private static final HttpServer CORE = upstream("core");
    private static final HttpServer ORDER = upstream("order");

    @Autowired
    private WebTestClient client;

    private static HttpServer upstream(String service) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                var headers = exchange.getRequestHeaders();
                byte[] body = new ObjectMapper().writeValueAsBytes(Map.of(
                        "service", service,
                        "uri", exchange.getRequestURI().toString(),
                        "authorization", headers.getFirst("authorization") == null ? "" : headers.getFirst("authorization"),
                        "hasInternalToken", headers.containsKey("X-Ticket-Internal-Token"),
                        "hasUserId", headers.containsKey("X-User-Id")));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
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
        registry.add("ticket.routes.core", () -> "http://127.0.0.1:" + CORE.getAddress().getPort());
        registry.add("ticket.routes.order", () -> "http://127.0.0.1:" + ORDER.getAddress().getPort());
    }

    @AfterAll
    static void stopUpstreams() {
        CORE.stop(0);
        ORDER.stop(0);
    }

    @Test
    void tradingEndpointsGoToOrderService() {
        for (String path : new String[]{"/ticket", "/ticket/of/event/1", "/ticket-orders/me",
                "/ticket-reservations/me", "/ticket-waitlists/me", "/user/sign", "/user/sign/status", "/user/credits/logs"}) {
            client.get().uri(path).exchange().expectStatus().isOk()
                    .expectBody().jsonPath("$.service").isEqualTo("order");
        }
    }

    @Test
    void coreAndUploadEndpointsKeepTheirRoutes() {
        for (String path : new String[]{"/user/me", "/event/1", "/event-review/1", "/event-category/list",
                "/follow/of/user/1", "/upload/image", "/uploads/test.jpg"}) {
            client.get().uri(path).exchange().expectStatus().isOk()
                    .expectBody().jsonPath("$.service").isEqualTo("core");
        }
    }

    @Test
    void sessionHeaderAndQueryAreForwardedButInternalHeadersAreRemoved() {
        client.post().uri("/ticket-waitlists/join/1?requestId=request-1&useCredits=true")
                .header("authorization", "session-token")
                .header("X-Ticket-Internal-Token", "forged-token")
                .header("X-User-Id", "1")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.authorization").isEqualTo("session-token")
                .jsonPath("$.uri").isEqualTo("/ticket-waitlists/join/1?requestId=request-1&useCredits=true")
                .jsonPath("$.hasInternalToken").isEqualTo(false)
                .jsonPath("$.hasUserId").isEqualTo(false);
    }

    @Test
    void internalAndManagementEndpointsAreNotPublicRoutes() {
        client.get().uri("/internal/credits/1").exchange().expectStatus().isNotFound();
        client.get().uri("/actuator/env").exchange().expectStatus().isNotFound();
    }
}
