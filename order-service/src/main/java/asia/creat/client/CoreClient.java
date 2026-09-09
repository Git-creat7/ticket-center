package asia.creat.client;

import asia.creat.dto.EventSummaryDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "ticket-center-api", url = "${ticket.clients.core-url:}")
public interface CoreClient {

    @GetMapping("/internal/events/{eventId}")
    EventSummaryDTO getEvent(@PathVariable("eventId") Long eventId);

    @GetMapping("/internal/users/{userId}/admin")
    boolean isAdmin(@PathVariable("userId") Long userId);
}
