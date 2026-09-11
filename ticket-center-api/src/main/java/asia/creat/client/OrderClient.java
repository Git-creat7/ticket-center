package asia.creat.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "order-service", url = "${ticket.clients.order-url:}")
public interface OrderClient {

    @GetMapping("/internal/credits/{userId}")
    int getCredits(@PathVariable("userId") Long userId);
}
