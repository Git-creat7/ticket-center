package asia.creat.controller;

import asia.creat.common.PageResult;
import asia.creat.common.Result;
import asia.creat.dto.PageQuery;
import asia.creat.dto.PasswordLoginDTO;
import asia.creat.dto.SetPasswordDTO;
import asia.creat.dto.UserLoginDTO;
import asia.creat.dto.UserProfileUpdateDTO;
import asia.creat.entity.CreditLog;
import asia.creat.entity.UserInfo;
import asia.creat.service.CreditLogService;
import asia.creat.service.FollowService;
import asia.creat.service.SignService;
import asia.creat.service.UserInfoService;
import asia.creat.service.UserService;
import asia.creat.utils.UserHolder;
import asia.creat.vo.UserVO;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/user")
public class UserController {

    private final UserService userService;
    private final SignService signService;
    private final UserInfoService userInfoService;
    private final CreditLogService creditLogService;
    private final FollowService followService;

    @PostMapping("/code")
    public Result sendCode(@RequestParam("phone") String phone) {
        userService.sendCode(phone);
        return Result.success();
    }

    @PostMapping("/login")
    public Result login(@RequestBody @Validated UserLoginDTO loginDTO) {
        String token = userService.login(loginDTO);
        return Result.success(token);
    }

    @PostMapping("/login/password")
    public Result loginByPassword(@RequestBody @Validated PasswordLoginDTO loginDTO) {
        String token = userService.loginByPassword(loginDTO);
        return Result.success(token);
    }

    @PostMapping("/password")
    public Result setPassword(@RequestBody @Validated SetPasswordDTO passwordDTO) {
        userService.setPassword(passwordDTO);
        return Result.success();
    }

    @GetMapping("/password/status")
    public Result passwordStatus() {
        return Result.success(userService.hasPassword());
    }

    @PostMapping("/logout")
    public Result logout(@RequestHeader(value = "authorization", required = false) String token) {
        if (token != null) {
            userService.logout(token);
        }
        return Result.success();
    }

    @GetMapping("/me")
    public Result me() {
        UserVO user = userService.getCurrentUser();
        return Result.success(user);
    }

    @GetMapping("/info/{id}")
    public Result info(@PathVariable("id") Long userId) {
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            info = new UserInfo().setUserId(userId).setCredits(0);
        } else {
            info.setCreateTime(null);
            info.setUpdateTime(null);
        }
        info.setFollowee(followService.countFollowee(userId));
        info.setFans(followService.countFans(userId));
        return Result.success(info);
    }

    @GetMapping("/{id}")
    public Result queryUserById(@PathVariable("id") Long userId) {
        UserVO user = userService.queryUserById(userId);
        return Result.success(user);
    }

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
        Long userId = UserHolder.getUser().getId();
        PageResult<CreditLog> result = creditLogService.queryUserCreditLogs(userId, query);
        return Result.success(result);
    }

    @PutMapping("/profile")
    public Result updateProfile(@RequestBody(required = false) @Validated UserProfileUpdateDTO updateDTO,
                                @RequestHeader(value = "authorization", required = false) String token) {
        userService.updateProfile(updateDTO, token);
        return Result.success();
    }
}
