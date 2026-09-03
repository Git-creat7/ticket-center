package asia.creat.controller;

import asia.creat.common.Result;
import asia.creat.dto.TicketCreateDTO;
import asia.creat.service.TicketService;
import asia.creat.utils.RequireAdmin;
import asia.creat.vo.TicketVO;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/ticket")
public class TicketController {

    private final TicketService ticketService;

    @GetMapping("/of/event/{eventId}")
    public Result queryTicketOfEvent(@PathVariable Long eventId) {
        List<TicketVO> tickets = ticketService.queryTicketOfEvent(eventId);
        return Result.success(tickets);
    }

    @RequireAdmin
    @PostMapping
    public Result addTicket(@RequestBody @Validated TicketCreateDTO createDTO) {
        Long ticketId = ticketService.addTicket(createDTO);
        return Result.success(ticketId);
    }
}
