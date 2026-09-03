package asia.creat.controller;

import asia.creat.common.PageResult;
import asia.creat.common.Result;
import asia.creat.dto.EventReviewCreateDTO;
import asia.creat.dto.PageQuery;
import asia.creat.dto.ScrollQueryDTO;
import asia.creat.service.EventReviewService;
import asia.creat.vo.EventReviewVO;
import asia.creat.vo.ScrollResultVO;
import asia.creat.vo.UserVO;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/event-review")
public class EventReviewController {

    private final EventReviewService reviewService;

    @PostMapping
    public Result saveReview(@RequestBody @Validated EventReviewCreateDTO createDTO) {
        Long id = reviewService.saveReview(createDTO);
        return Result.success(id);
    }

    @DeleteMapping("/{id}")
    public Result deleteReview(@PathVariable Long id) {
        reviewService.deleteReview(id);
        return Result.success();
    }

    @PutMapping("/like/{id}")
    public Result likeReview(@PathVariable Long id) {
        reviewService.likeReview(id);
        return Result.success();
    }

    @GetMapping("/hot")
    public Result queryHotReview(@Validated PageQuery query) {
        List<EventReviewVO> list = reviewService.queryHotReview(query);
        return Result.success(list);
    }

    @GetMapping("/of/user/{id}")
    public Result queryReviewsByUser(@PathVariable Long id, @Validated PageQuery query) {
        PageResult<EventReviewVO> reviews = reviewService.queryReviewsByUser(id, query);
        return Result.success(reviews);
    }

    @GetMapping("/{id}")
    public Result queryReviewById(@PathVariable Long id) {
        EventReviewVO vo = reviewService.queryReviewById(id);
        return Result.success(vo);
    }

    @GetMapping("/likes/{id}")
    public Result queryReviewLikes(@PathVariable Long id) {
        List<UserVO> users = reviewService.queryReviewLikes(id);
        return Result.success(users);
    }

    @GetMapping("/of/follow")
    public Result queryReviewOfFollow(@Validated ScrollQueryDTO queryDTO) {
        ScrollResultVO<EventReviewVO> result = reviewService.queryReviewOfFollow(queryDTO);
        return Result.success(result);
    }
}
