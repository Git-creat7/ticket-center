package asia.creat.controller;

import asia.creat.common.PageResult;
import asia.creat.common.Result;
import asia.creat.dto.PageQuery;
import asia.creat.service.FollowService;
import asia.creat.vo.UserVO;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/follow")
public class FollowController {

    private final FollowService followService;

    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable("id") Long id,
                         @PathVariable("isFollow") boolean isFollow) {
        followService.follow(id, isFollow);
        return Result.success();
    }

    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id") Long id) {
        boolean follow = followService.isFollow(id);
        return Result.success(follow);
    }

    @GetMapping("/followees/{id}")
    public Result followees(@PathVariable("id") Long id, @Validated PageQuery query) {
        PageResult<UserVO> users = followService.queryFollowees(id, query);
        return Result.success(users);
    }

    @GetMapping("/fans/{id}")
    public Result fans(@PathVariable("id") Long id, @Validated PageQuery query) {
        PageResult<UserVO> users = followService.queryFans(id, query);
        return Result.success(users);
    }
}
