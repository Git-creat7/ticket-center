package asia.creat.review;

import asia.creat.dto.EventReviewCreateDTO;
import asia.creat.dto.UserDTO;
import asia.creat.entity.Event;
import asia.creat.entity.EventReview;
import asia.creat.entity.Follow;
import asia.creat.entity.User;
import asia.creat.mapper.EventMapper;
import asia.creat.mapper.EventReviewMapper;
import asia.creat.mapper.FollowMapper;
import asia.creat.mapper.UserMapper;
import asia.creat.service.EventReviewService;
import asia.creat.service.FollowService;
import asia.creat.support.IntegrationTestcontainers;
import asia.creat.utils.RedisConstants;
import asia.creat.utils.UserHolder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@SpringBootTest
public class FollowFeedTest extends IntegrationTestcontainers {

    @Autowired
    private FollowService followService;

    @Autowired
    private EventReviewService eventReviewService;

    @Autowired
    private FollowMapper followMapper;

    @Autowired
    private EventReviewMapper eventReviewMapper;

    @Autowired
    private EventMapper eventMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private final List<Long> reviewIds = new ArrayList<>();
    private Long followerId;
    private Long authorId;
    private Long eventId;

    @BeforeEach
    void setUp() {
        followerId = insertUser("feed-follower");
        authorId = insertUser("feed-author");

        Event event = new Event()
                .setName("follow-feed-test")
                .setCategoryId(1L)
                .setStartTime(LocalDateTime.now().plusDays(1))
                .setComments(0);
        eventMapper.insert(event);
        eventId = event.getId();
    }

    @AfterEach
    void tearDown() {
        stringRedisTemplate.delete(RedisConstants.FEED_KEY + followerId);
        followMapper.delete(new LambdaQueryWrapper<Follow>()
                .eq(Follow::getUserId, followerId)
                .eq(Follow::getFollowUserId, authorId));
        if (!reviewIds.isEmpty()) {
            eventReviewMapper.deleteByIds(reviewIds);
        }
        eventMapper.deleteById(eventId);
        userMapper.deleteById(followerId);
        userMapper.deleteById(authorId);
        UserHolder.removeUser();
    }

    @Test
    @DisplayName("关注作者时回填最近 20 条动态")
    void testFollowBackfillsLatestReviews() {
        LocalDateTime start = LocalDateTime.now().minusDays(1);
        for (int i = 0; i < 25; i++) {
            insertReview(start.plusMinutes(i));
        }

        holdUser(followerId);
        followService.follow(authorId, true);

        Set<String> feed = stringRedisTemplate.opsForZSet()
                .range(RedisConstants.FEED_KEY + followerId, 0, -1);
        Set<String> expected = reviewIds.subList(5, 25).stream()
                .map(String::valueOf)
                .collect(java.util.stream.Collectors.toSet());
        Assertions.assertEquals(expected, feed);

        stringRedisTemplate.delete(RedisConstants.FEED_KEY + followerId);
        followService.follow(authorId, true);
        Assertions.assertEquals(expected, stringRedisTemplate.opsForZSet()
                .range(RedisConstants.FEED_KEY + followerId, 0, -1));
    }

    @Test
    @DisplayName("关注后发布的新评价立即进入动态流")
    void testNewReviewIsPushedAfterFollow() {
        holdUser(followerId);
        followService.follow(authorId, true);

        holdUser(authorId);
        EventReviewCreateDTO dto = new EventReviewCreateDTO();
        dto.setEventId(eventId);
        dto.setTitle("new review");
        dto.setContent("follow-feed-test");
        Long reviewId = eventReviewService.saveReview(dto);
        reviewIds.add(reviewId);

        Double score = stringRedisTemplate.opsForZSet()
                .score(RedisConstants.FEED_KEY + followerId, reviewId.toString());
        Assertions.assertNotNull(score);
    }

    @Test
    @DisplayName("取关后移除该作者的全部动态")
    void testUnfollowRemovesAuthorReviews() {
        insertReview(LocalDateTime.now().minusMinutes(2));
        insertReview(LocalDateTime.now().minusMinutes(1));
        holdUser(followerId);
        followService.follow(authorId, true);
        stringRedisTemplate.opsForZSet().add(
                RedisConstants.FEED_KEY + followerId, "999999999", System.currentTimeMillis());

        followService.follow(authorId, false);
        followService.follow(authorId, false);

        Set<String> feed = stringRedisTemplate.opsForZSet()
                .range(RedisConstants.FEED_KEY + followerId, 0, -1);
        Assertions.assertEquals(Set.of("999999999"), feed == null ? Set.of() : new HashSet<>(feed));
    }

    private Long insertUser(String nickName) {
        User user = new User()
                .setPhone("138" + String.format("%08d", System.nanoTime() % 100_000_000L))
                .setNickName(nickName);
        userMapper.insert(user);
        return user.getId();
    }

    private void insertReview(LocalDateTime createTime) {
        EventReview review = new EventReview()
                .setEventId(eventId)
                .setUserId(authorId)
                .setTitle("follow-feed-test")
                .setContent("follow-feed-test")
                .setLiked(0)
                .setComments(0)
                .setCreateTime(createTime);
        eventReviewMapper.insert(review);
        reviewIds.add(review.getId());
    }

    private void holdUser(Long userId) {
        UserDTO user = new UserDTO();
        user.setId(userId);
        UserHolder.saveUser(user);
    }
}
