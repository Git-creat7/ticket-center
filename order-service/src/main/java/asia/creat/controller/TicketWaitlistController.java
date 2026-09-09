package asia.creat.controller;

import asia.creat.common.Result;
import asia.creat.dto.PageQuery;
import asia.creat.service.TicketWaitlistService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/ticket-waitlists")
public class TicketWaitlistController {

    private final TicketWaitlistService waitlistService;

    @PostMapping("/join/{ticketId}")
    public Result join(@PathVariable Long ticketId,
                       @RequestParam(defaultValue = "false") Boolean useCredits,
                       @RequestParam(required = false) String requestId) {
        return Result.success(waitlistService.join(ticketId, useCredits, requestId));
    }

    @PostMapping("/cancel/{id}")
    public Result cancel(@PathVariable Long id) {
        waitlistService.cancel(id);
        return Result.success();
    }

    @GetMapping("/me")
    public Result myWaitlists(@Validated PageQuery query) {
        return Result.success(waitlistService.myWaitlists(query));
    }

    @GetMapping("/{id}")
    public Result getWaitlist(@PathVariable Long id) {
        return Result.success(waitlistService.getWaitlist(id));
    }
}
