package asia.creat.client;

import asia.creat.vo.TicketVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;

@FeignClient(name = "order-service", url = "${ticket.clients.order-url:}")
public interface OrderClient {

    @GetMapping("/internal/tickets/of/event/{eventId}")
    List<TicketVO> queryTickets(@PathVariable("eventId") Long eventId);

    @GetMapping("/internal/credits/{userId}")
    int getCredits(@PathVariable("userId") Long userId);
}
