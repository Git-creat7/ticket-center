package asia.creat.service.impl;

import asia.creat.common.PageResult;
import asia.creat.common.exception.BusinessException;
import asia.creat.dto.PageQuery;
import asia.creat.entity.EventReview;
import asia.creat.entity.Follow;
import asia.creat.mapper.EventReviewMapper;
import asia.creat.mapper.FollowMapper;
import asia.creat.service.FollowService;
import asia.creat.service.UserService;
import asia.creat.utils.UserHolder;
import asia.creat.vo.UserVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static asia.creat.utils.RedisConstants.FEED_KEY;

@Service
@RequiredArgsConstructor
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements FollowService {

    private static final int FEED_BACKFILL_LIMIT = 20;

    private final FollowMapper followMapper;
    private final EventReviewMapper eventReviewMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final UserService userService;

    @Override
    public void follow(Long targetUserId, boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        if (Objects.equals(userId, targetUserId)) {
            throw new BusinessException("不能关注自己");
        }
        if (isFollow) {
            LambdaQueryWrapper<Follow> existsWrapper = new LambdaQueryWrapper<Follow>()
                    .eq(Follow::getUserId, userId)
                    .eq(Follow::getFollowUserId, targetUserId);
            if (exists(existsWrapper)) {
                backfillFeed(userId, targetUserId);
                return;
            }
            // tb_follow 没有指向 tb_user 的外键，伪造的 targetUserId 插进来照样成功，
            // 所以这里先校验目标用户。放在幂等判断之后，重复关注不多查一次库。
            if (userService.getById(targetUserId) == null) {
                throw new BusinessException(404, "目标用户不存在");
            }
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(targetUserId);

            boolean save = save(follow);
            if (save) {
                backfillFeed(userId, targetUserId);
            }
        } else {
            LambdaQueryWrapper<Follow> lqw = new LambdaQueryWrapper<Follow>()
                    .eq(Follow::getUserId, userId)
                    .eq(Follow::getFollowUserId, targetUserId);

            remove(lqw);
            removeAuthorReviewsFromFeed(userId, targetUserId);
        }
    }

    @Override
    public boolean isFollow(Long targetUserId) {
        Long userId = UserHolder.getUser().getId();
        LambdaQueryWrapper<Follow> lqw = new LambdaQueryWrapper<Follow>()
                .eq(Follow::getUserId, userId)
                .eq(Follow::getFollowUserId, targetUserId);
        Long count = followMapper.selectCount(lqw);
        return count != null && count > 0;
    }

    @Override
    public PageResult<UserVO> queryFollowees(Long userId, PageQuery query) {
        Page<Follow> page = followMapper.selectPage(query.toPage(), new LambdaQueryWrapper<Follow>()
                .eq(Follow::getUserId, userId)
                .orderByDesc(Follow::getCreateTime)
                .orderByDesc(Follow::getId));
        List<Long> ids = page.getRecords().stream().map(Follow::getFollowUserId).toList();
        return toUserPage(page, ids);
    }

    @Override
    public PageResult<UserVO> queryFans(Long userId, PageQuery query) {
        Page<Follow> page = followMapper.selectPage(query.toPage(), new LambdaQueryWrapper<Follow>()
                .eq(Follow::getFollowUserId, userId)
                .orderByDesc(Follow::getCreateTime)
                .orderByDesc(Follow::getId));
        List<Long> ids = page.getRecords().stream().map(Follow::getUserId).toList();
        return toUserPage(page, ids);
    }

    private PageResult<UserVO> toUserPage(Page<Follow> page, List<Long> ids) {
        List<UserVO> users = userService.queryUsersByIdsSorted(ids);
        return PageResult.of(users, page.getTotal(), page.getCurrent(), page.getSize());
    }

    private void backfillFeed(Long userId, Long authorId) {
        Page<EventReview> page = new Page<>(1, FEED_BACKFILL_LIMIT, false);
        eventReviewMapper.selectPage(page, new LambdaQueryWrapper<EventReview>()
                .select(EventReview::getId, EventReview::getCreateTime)
                .eq(EventReview::getUserId, authorId)
                .orderByDesc(EventReview::getCreateTime)
                .orderByDesc(EventReview::getId));

        Set<ZSetOperations.TypedTuple<String>> reviews = page.getRecords().stream()
                .map(review -> new DefaultTypedTuple<>(
                        review.getId().toString(),
                        (double) review.getCreateTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()))
                .collect(Collectors.toSet());
        if (!reviews.isEmpty()) {
            stringRedisTemplate.opsForZSet().add(FEED_KEY + userId, reviews);
        }
    }

    private void removeAuthorReviewsFromFeed(Long userId, Long authorId) {
        List<Object> reviewIds = eventReviewMapper.selectObjs(new LambdaQueryWrapper<EventReview>()
                .select(EventReview::getId)
                .eq(EventReview::getUserId, authorId));
        if (!reviewIds.isEmpty()) {
            Object[] members = reviewIds.stream().map(String::valueOf).toArray();
            stringRedisTemplate.opsForZSet().remove(FEED_KEY + userId, members);
        }
    }

    // 关注数与粉丝数直接从 tb_follow 数，不用 tb_user_info 里那两列。
    // 那两列只在建行时写 0，从来没有维护过，页面上永远显示 0 关注 0 粉丝。
    // 选择实时统计而不是补上写时自增：这两个数是活的聚合，不是下单价那种历史事实，
    // 存起来就要处理关注/取关两条路径的漂移，而 follow() 目前没有事务包住 DB 与 Redis。
    // 两个方向都有覆盖索引（uk_follow_user_target 与 idx_follow_target），
    // 且只在个人主页这一处冷路径调用。
    @Override
    public int countFollowee(Long userId) {
        Long count = followMapper.selectCount(new LambdaQueryWrapper<Follow>()
                .eq(Follow::getUserId, userId));
        return count == null ? 0 : count.intValue();
    }

    @Override
    public int countFans(Long userId) {
        Long count = followMapper.selectCount(new LambdaQueryWrapper<Follow>()
                .eq(Follow::getFollowUserId, userId));
        return count == null ? 0 : count.intValue();
    }

}
