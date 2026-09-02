package asia.creat.service.impl;

import asia.creat.common.PageResult;
import asia.creat.common.exception.BusinessException;
import asia.creat.dto.EventReviewCommentCreateDTO;
import asia.creat.dto.PageQuery;
import asia.creat.entity.EventReview;
import asia.creat.entity.EventReviewComment;
import asia.creat.entity.User;
import asia.creat.mapper.EventReviewCommentMapper;
import asia.creat.mapper.EventReviewMapper;
import asia.creat.service.EventReviewCommentService;
import asia.creat.service.UserService;
import asia.creat.utils.UserHolder;
import asia.creat.vo.EventReviewCommentVO;
import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EventReviewCommentServiceImpl
        extends ServiceImpl<EventReviewCommentMapper, EventReviewComment>
        implements EventReviewCommentService {

    private final EventReviewMapper eventReviewMapper;
    private final UserService userService;

    @Override
    @Transactional
    public Long saveComment(Long reviewId, EventReviewCommentCreateDTO createDTO) {
        EventReview review = eventReviewMapper.selectById(reviewId);
        if (review == null) {
            throw new BusinessException(404, "动态不存在");
        }

        EventReviewComment comment = new EventReviewComment()
                .setReviewId(reviewId)
                .setUserId(UserHolder.getUser().getId())
                .setContent(createDTO.getContent().trim());
        if (!save(comment)) {
            throw new BusinessException("评论发布失败");
        }

        int updated = eventReviewMapper.update(null, new LambdaUpdateWrapper<EventReview>()
                .setSql("comments = COALESCE(comments, 0) + 1")
                .eq(EventReview::getId, reviewId));
        if (updated != 1) {
            throw new BusinessException(404, "动态不存在");
        }
        return comment.getId();
    }

    @Override
    public PageResult<EventReviewCommentVO> queryComments(Long reviewId, PageQuery query) {
        if (eventReviewMapper.selectById(reviewId) == null) {
            throw new BusinessException(404, "动态不存在");
        }

        Page<EventReviewComment> page = lambdaQuery()
                .eq(EventReviewComment::getReviewId, reviewId)
                .orderByDesc(EventReviewComment::getCreateTime)
                .orderByDesc(EventReviewComment::getId)
                .page(query.toPage());
        List<EventReviewCommentVO> comments = toCommentVOList(page.getRecords());
        return PageResult.of(comments, page.getTotal(), page.getCurrent(), page.getSize());
    }

    @Override
    @Transactional
    public void deleteComment(Long commentId) {
        EventReviewComment comment = getById(commentId);
        if (comment == null) {
            throw new BusinessException(404, "评论不存在");
        }

        Long currentUserId = UserHolder.getUser().getId();
        if (!Objects.equals(comment.getUserId(), currentUserId)) {
            throw new BusinessException(403, "只能删除自己的评论");
        }

        int deleted = baseMapper.delete(new LambdaQueryWrapper<EventReviewComment>()
                .eq(EventReviewComment::getId, commentId)
                .eq(EventReviewComment::getUserId, currentUserId));
        if (deleted != 1) {
            throw new BusinessException(404, "评论不存在");
        }

        eventReviewMapper.update(null, new LambdaUpdateWrapper<EventReview>()
                .setSql("comments = GREATEST(COALESCE(comments, 0) - 1, 0)")
                .eq(EventReview::getId, comment.getReviewId()));
    }

    private List<EventReviewCommentVO> toCommentVOList(List<EventReviewComment> comments) {
        if (comments.isEmpty()) {
            return List.of();
        }

        Set<Long> userIds = comments.stream()
                .map(EventReviewComment::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, User> users = userIds.isEmpty() ? Map.of()
                : userService.listByIds(userIds).stream()
                        .collect(Collectors.toMap(User::getId, user -> user, (first, ignored) -> first));

        List<EventReviewCommentVO> result = new ArrayList<>(comments.size());
        for (EventReviewComment comment : comments) {
            EventReviewCommentVO vo = BeanUtil.copyProperties(comment, EventReviewCommentVO.class);
            User user = users.get(comment.getUserId());
            if (user != null) {
                vo.setUserName(user.getNickName());
                vo.setUserIcon(user.getIcon());
            }
            result.add(vo);
        }
        return result;
    }
}
