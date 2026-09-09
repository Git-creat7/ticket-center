package asia.creat.controller;

import asia.creat.common.Result;
import asia.creat.dto.PageQuery;
import asia.creat.service.TicketReservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/ticket-reservations")
public class TicketReservationController {

    private final TicketReservationService reservationService;

    @GetMapping("/me")
    public Result myReservations(@Validated PageQuery query) {
        return Result.success(reservationService.myReservations(query));
    }

    @GetMapping("/{id}")
    public Result getReservation(@PathVariable Long id) {
        return Result.success(reservationService.getReservation(id));
    }
}
