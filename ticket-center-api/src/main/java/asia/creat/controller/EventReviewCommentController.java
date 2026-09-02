package asia.creat.controller;

import asia.creat.common.PageResult;
import asia.creat.common.Result;
import asia.creat.dto.EventReviewCommentCreateDTO;
import asia.creat.dto.PageQuery;
import asia.creat.service.EventReviewCommentService;
import asia.creat.vo.EventReviewCommentVO;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/event-review")
public class EventReviewCommentController {

    private final EventReviewCommentService commentService;

    @GetMapping("/{reviewId}/comments")
    public Result queryComments(@PathVariable Long reviewId, @Validated PageQuery query) {
        PageResult<EventReviewCommentVO> comments = commentService.queryComments(reviewId, query);
        return Result.success(comments);
    }

    @PostMapping("/{reviewId}/comments")
    public Result saveComment(@PathVariable Long reviewId,
                              @RequestBody @Validated EventReviewCommentCreateDTO createDTO) {
        return Result.success(commentService.saveComment(reviewId, createDTO));
    }

    @DeleteMapping("/comments/{commentId}")
    public Result deleteComment(@PathVariable Long commentId) {
        commentService.deleteComment(commentId);
        return Result.success();
    }
}
