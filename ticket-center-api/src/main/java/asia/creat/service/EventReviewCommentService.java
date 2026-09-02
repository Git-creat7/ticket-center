package asia.creat.service;

import asia.creat.common.PageResult;
import asia.creat.dto.EventReviewCommentCreateDTO;
import asia.creat.dto.PageQuery;
import asia.creat.entity.EventReviewComment;
import asia.creat.vo.EventReviewCommentVO;
import com.baomidou.mybatisplus.extension.service.IService;

public interface EventReviewCommentService extends IService<EventReviewComment> {

    Long saveComment(Long reviewId, EventReviewCommentCreateDTO createDTO);

    PageResult<EventReviewCommentVO> queryComments(Long reviewId, PageQuery query);

    void deleteComment(Long commentId);
}
