package asia.creat.controller;

import asia.creat.common.Result;
import asia.creat.dto.PageQuery;
import asia.creat.service.CreditLogService;
import asia.creat.service.SignService;
import asia.creat.utils.UserHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/user")
public class CreditController {

    private final SignService signService;
    private final CreditLogService creditLogService;

    @PostMapping("/sign")
    public Result sign() {
        signService.sign();
        return Result.success();
    }

    @GetMapping("/sign/status")
    public Result getSignStatus() {
        return Result.success(signService.getSignStatus());
    }

    @GetMapping("/credits/logs")
    public Result creditLogs(@Validated PageQuery query) {
        return Result.success(creditLogService.queryUserCreditLogs(UserHolder.getUser().getId(), query));
    }
}
