package asia.creat.review;

import asia.creat.common.PageResult;
import asia.creat.common.exception.BusinessException;
import asia.creat.dto.EventReviewCommentCreateDTO;
import asia.creat.dto.EventReviewCreateDTO;
import asia.creat.dto.PageQuery;
import asia.creat.dto.UserDTO;
import asia.creat.entity.Event;
import asia.creat.entity.EventReviewComment;
import asia.creat.entity.User;
import asia.creat.mapper.EventMapper;
import asia.creat.mapper.UserMapper;
import asia.creat.service.EventReviewCommentService;
import asia.creat.service.EventReviewService;
import asia.creat.utils.RedisConstants;
import asia.creat.utils.UserHolder;
import asia.creat.vo.EventReviewCommentVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class EventReviewCommentTest {

    @Autowired
    private EventReviewService reviewService;

    @Autowired
    private EventReviewCommentService commentService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private EventMapper eventMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private MockMvc mockMvc;

    private final List<Long> userIds = new ArrayList<>();
    private final List<String> tokenKeys = new ArrayList<>();
    private Long authorId;
    private Long commenterId;
    private Long eventId;
    private Long reviewId;

    @BeforeEach
    void setUp() {
        authorId = createUser("comment-author");
        commenterId = createUser("comment-user");

        Event event = new Event()
                .setName("review-comment-test")
                .setCategoryId(1L)
                .setStartTime(LocalDateTime.now().plusDays(10))
                .setComments(0);
        eventMapper.insert(event);
        eventId = event.getId();

        loginAs(authorId, "comment-author");
        EventReviewCreateDTO review = new EventReviewCreateDTO();
        review.setEventId(eventId);
        review.setTitle("review-comment-test");
        review.setContent("review-comment-test");
        reviewId = reviewService.saveReview(review);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
        if (reviewId != null) {
            commentService.lambdaUpdate().eq(EventReviewComment::getReviewId, reviewId).remove();
            reviewService.removeById(reviewId);
            stringRedisTemplate.delete(RedisConstants.REVIEW_LIKED_KEY + reviewId);
        }
        if (eventId != null) {
            eventMapper.deleteById(eventId);
        }
        for (Long userId : userIds) {
            userMapper.deleteById(userId);
        }
        stringRedisTemplate.delete(tokenKeys);
        userIds.clear();
        tokenKeys.clear();
    }

    @Test
    @DisplayName("评论按最新优先分页，并带回评论者信息")
    void commentsArePagedWithAuthorInfo() {
        loginAs(commenterId, "comment-user");
        Long first = saveComment("第一条");
        Long second = saveComment("第二条");

        PageQuery query = new PageQuery();
        query.setSize(1);
        PageResult<EventReviewCommentVO> firstPage = commentService.queryComments(reviewId, query);

        Assertions.assertEquals(2, firstPage.getTotal());
        Assertions.assertEquals(2, firstPage.getPages());
        Assertions.assertEquals(List.of(second), firstPage.getRecords().stream().map(EventReviewCommentVO::getId).toList());
        Assertions.assertEquals("comment-user", firstPage.getRecords().get(0).getUserName());
        Assertions.assertEquals(2, reviewService.getById(reviewId).getComments());

        query.setCurrent(2);
        PageResult<EventReviewCommentVO> secondPage = commentService.queryComments(reviewId, query);
        Assertions.assertEquals(List.of(first), secondPage.getRecords().stream().map(EventReviewCommentVO::getId).toList());
    }

    @Test
    @DisplayName("评论只能由评论者本人删除，成功后评论数减一")
    void onlyCommentOwnerCanDelete() {
        loginAs(commenterId, "comment-user");
        Long commentId = saveComment("准备删除");

        loginAs(authorId, "comment-author");
        BusinessException forbidden = Assertions.assertThrows(
                BusinessException.class,
                () -> commentService.deleteComment(commentId));
        Assertions.assertEquals(403, forbidden.getCode());
        Assertions.assertNotNull(commentService.getById(commentId));

        loginAs(commenterId, "comment-user");
        commentService.deleteComment(commentId);
        Assertions.assertNull(commentService.getById(commentId));
        Assertions.assertEquals(0, reviewService.getById(reviewId).getComments());
    }

    @Test
    @DisplayName("动态只能由作者删除")
    void onlyReviewAuthorCanDelete() {
        loginAs(commenterId, "comment-user");

        BusinessException forbidden = Assertions.assertThrows(
                BusinessException.class,
                () -> reviewService.deleteReview(reviewId));
        Assertions.assertEquals(403, forbidden.getCode());
        Assertions.assertNotNull(reviewService.getById(reviewId));
    }

    @Test
    @DisplayName("删除动态会级联评论、回减活动评价数并清理点赞缓存")
    void deletingReviewCleansRelatedData() {
        loginAs(commenterId, "comment-user");
        Long commentId = saveComment("随动态删除");
        reviewService.likeReview(reviewId);
        Assertions.assertTrue(Boolean.TRUE.equals(
                stringRedisTemplate.hasKey(RedisConstants.REVIEW_LIKED_KEY + reviewId)));

        loginAs(authorId, "comment-author");
        reviewService.deleteReview(reviewId);

        Assertions.assertNull(reviewService.getById(reviewId));
        Assertions.assertNull(commentService.getById(commentId));
        Assertions.assertEquals(0, eventMapper.selectById(eventId).getComments());
        Assertions.assertFalse(Boolean.TRUE.equals(
                stringRedisTemplate.hasKey(RedisConstants.REVIEW_LIKED_KEY + reviewId)));
        reviewId = null;
    }

    @Test
    @DisplayName("评论列表允许公开查看，发布评论必须登录")
    void commentEndpointsUseExpectedAuthentication() throws Exception {
        mockMvc.perform(get("/event-review/{id}/comments", reviewId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(0));

        mockMvc.perform(post("/event-review/{id}/comments", reviewId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"web comment\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(post("/event-review/{id}/comments", reviewId)
                        .header("authorization", createToken(commenterId, "comment-user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"web comment\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        Assertions.assertEquals(1, reviewService.getById(reviewId).getComments());
    }

    private Long saveComment(String content) {
        EventReviewCommentCreateDTO dto = new EventReviewCommentCreateDTO();
        dto.setContent(content);
        return commentService.saveComment(reviewId, dto);
    }

    private Long createUser(String nickName) {
        String phone = "137" + String.format("%08d", System.nanoTime() % 100_000_000L);
        User user = new User().setPhone(phone).setNickName(nickName);
        userMapper.insert(user);
        userIds.add(user.getId());
        return user.getId();
    }

    private void loginAs(Long userId, String nickName) {
        UserDTO user = new UserDTO();
        user.setId(userId);
        user.setNickName(nickName);
        UserHolder.saveUser(user);
    }

    private String createToken(Long userId, String nickName) {
        String token = UUID.randomUUID().toString().replace("-", "");
        String tokenKey = RedisConstants.LOGIN_USER_KEY + token;
        Map<String, String> userMap = new HashMap<>();
        userMap.put("id", String.valueOf(userId));
        userMap.put("nickName", nickName);
        userMap.put("icon", "");
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        stringRedisTemplate.expire(tokenKey, RedisConstants.LOGIN_USER_TTL);
        tokenKeys.add(tokenKey);
        return token;
    }
}
