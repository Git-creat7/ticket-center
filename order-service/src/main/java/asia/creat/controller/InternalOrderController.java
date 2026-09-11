package asia.creat.controller;

import asia.creat.service.CreditAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal")
public class InternalOrderController {

    private final CreditAccountService creditAccountService;

    @GetMapping("/credits/{userId}")
    public int getCredits(@PathVariable Long userId) {
        return creditAccountService.getBalance(userId);
    }
}
