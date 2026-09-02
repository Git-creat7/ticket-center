package asia.creat.service.impl;

import asia.creat.common.PageResult;
import asia.creat.common.exception.BusinessException;
import asia.creat.dto.EventReviewCreateDTO;
import asia.creat.dto.PageQuery;
import asia.creat.dto.ScrollQueryDTO;
import asia.creat.dto.UserDTO;
import asia.creat.entity.Event;
import asia.creat.entity.EventReview;
import asia.creat.entity.Follow;
import asia.creat.entity.User;
import asia.creat.mapper.EventMapper;
import asia.creat.mapper.EventReviewMapper;
import asia.creat.service.EventReviewService;
import asia.creat.service.FollowService;
import asia.creat.service.UserService;
import asia.creat.utils.UserHolder;
import asia.creat.vo.EventReviewVO;
import asia.creat.vo.ScrollResultVO;
import asia.creat.vo.UserVO;
import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static asia.creat.utils.RedisConstants.FEED_KEY;
import static asia.creat.utils.RedisConstants.REVIEW_LIKED_KEY;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventReviewServiceImpl extends ServiceImpl<EventReviewMapper, EventReview> implements EventReviewService {

    private final UserService userService;
    private final StringRedisTemplate stringRedisTemplate;
    private final FollowService followService;
    private final EventMapper eventMapper;

    @Override
    public List<EventReviewVO> queryHotReview(PageQuery query) {
        Page<EventReview> page = query()
                .orderByDesc("liked")
                // liked 大量为 0，缺第二排序键时翻页顺序完全不确定，比热门演出更容易漏行/重行
                .orderByDesc("id")
                .page(query.toPage());

        return toReviewVOList(page.getRecords());
    }

    @Override
    public PageResult<EventReviewVO> queryReviewsByUser(Long userId, PageQuery query) {
        Page<EventReview> page = query()
                .eq("user_id", userId)
                .orderByDesc("create_time")
                .orderByDesc("id")
                .page(query.toPage());
        List<EventReviewVO> reviews = toReviewVOList(page.getRecords());
        return PageResult.of(reviews, page.getTotal(), page.getCurrent(), page.getSize());
    }

    @Override
    public EventReviewVO queryReviewById(Long id) {
        EventReview review = getById(id);
        if (review == null) {
            throw new BusinessException(404, "评价不存在");
        }
        return toReviewVO(review);
    }

    @Override
    public void likeReview(Long id) {
        Long userId = UserHolder.getUser().getId();
        String key = REVIEW_LIKED_KEY + id;

        // 不能先 ZSCORE 判断再改计数：并发下两个请求都读到未点赞，liked 会被加两次。
        // 改为直接用 ZREM / ZADD 的返回值判定——它们本身是原子的，只有真正改变了集合的那次请求才动 DB 计数。
        Long removed = stringRedisTemplate.opsForZSet().remove(key, userId.toString());
        if (removed != null && removed > 0) {
            // 本次调用把用户从点赞集合里移除了，说明是取消点赞
            update().setSql("liked = liked - 1").eq("id", id).update();
            return;
        }

        Boolean added = stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
        if (Boolean.TRUE.equals(added)) {
            // 只有新增成功（此前不在集合中）才加计数，重复点赞不会累加
            update().setSql("liked = liked + 1").eq("id", id).update();
        }
    }

    @Override
    public List<UserVO> queryReviewLikes(Long id) {
        String key = REVIEW_LIKED_KEY + id;
        // score 是点赞时间戳，用 reverseRange 取最近点赞的 5 个；
        // range 是升序，会返回最早点赞的 5 个，"谁赞过"应展示最新的
        Set<String> latest5 = stringRedisTemplate.opsForZSet().reverseRange(key, 0, 4);
        if (latest5 == null || latest5.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> ids = latest5.stream().map(Long::valueOf).toList();
        return userService.queryUsersByIdsSorted(ids);
    }

    @Override
    @Transactional
    public Long saveReview(EventReviewCreateDTO createDTO) {
        UserDTO user = UserHolder.getUser();
        EventReview review = BeanUtil.copyProperties(createDTO, EventReview.class);
        review.setUserId(user.getId());
        review.setLiked(0);
        review.setComments(0);

        boolean save = save(review);
        if (!save) {
            throw new BusinessException("新增评价失败！");
        }

        // 累加演出评价数。用 setSql 让 MySQL 自己加，不能先读出来再加：
        // 并发评价下两个请求都读到同一个旧值，其中一次的累加会被覆盖。
        // 与插入同一个事务，避免评价已落库而计数没加。
        int updated = eventMapper.update(null, new LambdaUpdateWrapper<Event>()
                .setSql("comments = comments + 1")
                .eq(Event::getId, review.getEventId()));
        if (updated != 1) {
            // 评价表没有指向 tb_event 的外键，event_id 不存在时插入照样成功。
            // 这里不抛：抛了会改变现有的"脏 event_id 也接受"行为，只留痕供排查
            log.warn("评价已保存但演出评价数未累加，event_id 可能不存在, reviewId={}, eventId={}",
                    review.getId(), review.getEventId());
        }

        pushToFollowerFeedAfterCommit(user.getId(), review.getId());

        return review.getId();
    }

    @Override
    @Transactional
    public void deleteReview(Long id) {
        EventReview review = getById(id);
        if (review == null) {
            throw new BusinessException(404, "动态不存在");
        }

        Long currentUserId = UserHolder.getUser().getId();
        if (!Objects.equals(review.getUserId(), currentUserId)) {
            throw new BusinessException(403, "只能删除自己的动态");
        }

        boolean removed = remove(new LambdaQueryWrapper<EventReview>()
                .eq(EventReview::getId, id)
                .eq(EventReview::getUserId, currentUserId));
        if (!removed) {
            throw new BusinessException(404, "动态不存在");
        }

        eventMapper.update(null, new LambdaUpdateWrapper<Event>()
                .setSql("comments = GREATEST(COALESCE(comments, 0) - 1, 0)")
                .eq(Event::getId, review.getEventId()));
        cleanupReviewCacheAfterCommit(review.getUserId(), review.getId());
    }

    private void cleanupReviewCacheAfterCommit(Long authorId, Long reviewId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    stringRedisTemplate.delete(REVIEW_LIKED_KEY + reviewId);
                    List<Follow> follows = followService.query().eq("follow_user_id", authorId).list();
                    for (Follow follow : follows) {
                        stringRedisTemplate.opsForZSet()
                                .remove(FEED_KEY + follow.getUserId(), reviewId.toString());
                    }
                } catch (Exception e) {
                    log.error("动态已删除，但 Redis 缓存清理失败, reviewId={}, authorId={}", reviewId, authorId, e);
                }
            }
        });
    }

    /**
     * 提交之后再把评价 id 推给粉丝（Feed 流）。
     *
     * 必须放在事务外：本方法加上 @Transactional 之后，扇出就跑在 commit 之前了，
     * 事务这时还可能回滚 —— 评价没落库，Feed 里却留下指向不存在评价的 id。
     *
     * MySQL 是唯一事实来源。这步失败只留告警：queryReviewOfFollow 取到查不出的 id
     * 会自己过滤掉，Feed 少一条不影响状态。
     */
    private void pushToFollowerFeedAfterCommit(Long authorId, Long reviewId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    List<Follow> follows = followService.query().eq("follow_user_id", authorId).list();
                    long score = System.currentTimeMillis();
                    for (Follow follow : follows) {
                        stringRedisTemplate.opsForZSet()
                                .add(FEED_KEY + follow.getUserId(), reviewId.toString(), score);
                    }
                } catch (Exception e) {
                    log.error("【Feed 推送失败】评价已落库，但粉丝 Feed 未更新，粉丝将看不到这条评价, reviewId={}, authorId={}",
                            reviewId, authorId, e);
                }
            }
        });
    }

    @Override
    public ScrollResultVO<EventReviewVO> queryReviewOfFollow(ScrollQueryDTO queryDTO) {
        Long userId = UserHolder.getUser().getId();
        String key = FEED_KEY + userId;
        long max = queryDTO.getMax();
        int offset = queryDTO.getOffset();
        int size = queryDTO.getSize();

        // ZREVRANGEBYSCORE key min max LIMIT offset count
        Set<ZSetOperations.TypedTuple<String>> tuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, size);

        if (tuples == null || tuples.isEmpty()) {
            return new ScrollResultVO<>();
        }

        List<Long> ids = new ArrayList<>(tuples.size());
        long minTime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            ids.add(Long.valueOf(tuple.getValue()));
            long time = tuple.getScore().longValue();

            if (time == minTime) {
                os++;
            } else {
                minTime = time;
                os = 1;
            }
        }

        List<EventReview> rawReviews = listByIds(ids);
        Map<Long, EventReview> reviewMap = rawReviews.stream()
                .collect(Collectors.toMap(EventReview::getId, Function.identity(), (k1, k2) -> k1));
        List<EventReview> reviews = ids.stream()
                .map(reviewMap::get)
                .filter(Objects::nonNull)
                .toList();
        List<EventReviewVO> voList = toReviewVOList(reviews);

        ScrollResultVO<EventReviewVO> r = new ScrollResultVO<>();
        r.setList(voList);
        r.setOffset(os);
        r.setMinTime(minTime);
        return r;
    }

    private EventReviewVO toReviewVO(EventReview review) {
        // 单条也走批量实现，避免同一套字段映射写两遍：批量大小为 1 时开销与原来一致
        return toReviewVOList(List.of(review)).get(0);
    }

    /**
     * 批量组装评价 VO。
     *
     * 原先是逐条 {@code toReviewVO}，每条各查一次作者（SQL）、各查一次点赞状态（Redis）：
     * 一页 10 条就是 1 次分页查询 + 10 次 SELECT + 10 次 ZSCORE = 21 次往返。
     * 现在固定 3 次：分页查询、一次 selectBatchIds、一次 pipeline。
     */
    private List<EventReviewVO> toReviewVOList(List<EventReview> reviews) {
        if (reviews.isEmpty()) {
            return List.of();
        }

        // 批量取作者，避免逐条各查一次（N+1）
        Set<Long> userIds = reviews.stream()
                .map(EventReview::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, User> userMap = userIds.isEmpty() ? Map.of()
                : userService.listByIds(userIds).stream()
                        .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));

        List<Boolean> likedFlags = queryLikedFlags(reviews);

        List<EventReviewVO> voList = new ArrayList<>(reviews.size());
        for (int i = 0; i < reviews.size(); i++) {
            EventReview review = reviews.get(i);
            EventReviewVO vo = BeanUtil.copyProperties(review, EventReviewVO.class);
            User user = userMap.get(review.getUserId());
            if (user != null) {
                vo.setUserName(user.getNickName());
                vo.setUserIcon(user.getIcon());
            }
            vo.setIsLike(likedFlags.get(i));
            voList.add(vo);
        }
        return voList;
    }

    /**
     * 批量查当前用户对这批评价的点赞状态，返回值与入参一一对位。
     *
     * 每条评价的点赞集合是各自独立的 key，ZMSCORE 只能查同一个 key 的多个 member，用不上；
     * 改用 pipeline 把 N 次 ZSCORE 压成一次往返。pipeline 的返回顺序与命令入队顺序一致，
     * 下标对位就靠这条保证。
     */
    private List<Boolean> queryLikedFlags(List<EventReview> reviews) {
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            return Collections.nCopies(reviews.size(), false);
        }

        String member = currentUser.getId().toString();
        List<Object> scores = stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            StringRedisConnection stringConn = (StringRedisConnection) connection;
            for (EventReview review : reviews) {
                stringConn.zScore(REVIEW_LIKED_KEY + review.getId(), member);
            }
            // 回调必须返回 null：pipeline 的结果由 executePipelined 统一收集
            return null;
        });

        return scores.stream().map(Objects::nonNull).toList();
    }
}
