package asia.creat.controller;

import asia.creat.common.PageResult;
import asia.creat.common.Result;
import asia.creat.dto.PageQuery;
import asia.creat.service.TicketOrderService;
import asia.creat.vo.TicketOrderVO;
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
@RequestMapping("/ticket-orders")
public class TicketOrderController {

    private final TicketOrderService ticketOrderService;

    @PostMapping("/reserve/{ticketId}")
    public Result reserveTicket(
            @PathVariable Long ticketId,
            @RequestParam(value = "useCredits", required = false, defaultValue = "false") Boolean useCredits) {
        Long orderId = ticketOrderService.reserveTicket(ticketId, useCredits);
        return Result.success(orderId);
    }

    @PostMapping("/pay/{orderId}")
    public Result pay(@PathVariable Long orderId) {
        ticketOrderService.pay(orderId);
        return Result.success();
    }

    @PostMapping("/cancel/{orderId}")
    public Result cancel(@PathVariable Long orderId) {
        ticketOrderService.cancel(orderId);
        return Result.success();
    }

    @GetMapping("/me")
    public Result myOrders(
            @Validated PageQuery query,
            @RequestParam(value = "status", required = false) Integer status
    ) {
        PageResult<TicketOrderVO> pageResult = ticketOrderService.myOrders(query, status);
        return Result.success(pageResult);
    }
}
