package asia.creat.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.server.WebFilter;

@Configuration
public class GatewayRoutes {

    @Bean
    public RouteLocator routes(RouteLocatorBuilder builder,
                               @Value("${ticket.routes.core:lb://ticket-center-api}") String core,
                               @Value("${ticket.routes.order:lb://order-service}") String order) {
        return builder.routes()
                .route("order-service", route -> route
                        .path("/ticket", "/ticket/**", "/ticket-orders/**", "/ticket-reservations/**",
                                "/ticket-waitlists/**", "/user/sign", "/user/sign/**", "/user/credits/**")
                        .uri(order))
                .route("ticket-center-api", route -> route
                        .path("/user/**", "/event", "/event/**", "/event-category/**", "/event-review/**",
                                "/follow/**", "/upload/**", "/uploads/**")
                        .uri(core))
                .build();
    }

    @Bean
    public WebFilter removeInternalHeaders() {
        return (exchange, chain) -> chain.filter(exchange.mutate().request(request -> request.headers(headers -> {
            headers.remove("X-Ticket-Internal-Token");
            headers.remove("X-User-Id");
            headers.remove("X-User-Role");
        })).build());
    }
}
