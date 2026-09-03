package asia.creat.review;

import asia.creat.common.PageResult;
import asia.creat.dto.EventReviewCreateDTO;
import asia.creat.dto.PageQuery;
import asia.creat.dto.UserDTO;
import asia.creat.entity.Event;
import asia.creat.entity.User;
import asia.creat.mapper.EventMapper;
import asia.creat.mapper.UserMapper;
import asia.creat.service.EventReviewService;
import asia.creat.support.IntegrationTestcontainers;
import asia.creat.utils.RedisConstants;
import asia.creat.utils.UserHolder;
import asia.creat.vo.EventReviewVO;
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
import java.util.List;

// 评价列表批量组装与评价数累加。
@SpringBootTest
public class EventReviewBatchTest extends IntegrationTestcontainers {

    /** 作者昵称，用来断言批量组装有没有把作者填上 */
    private static final String AUTHOR_NICK = "review-batch-test";

    /** 顶高 liked，保证本测试造的评价排在热门评价第一页 */
    private static final int LIKED_BOOST = 999_999;

    @Autowired
    private EventReviewService eventReviewService;

    @Autowired
    private EventMapper eventMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private Long userId;
    private Long eventId;
    private final List<Long> createdReviewIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // 创建真实用户，验证批量查询能填充作者信息并避免测试数据冲突。
        User author = new User()
                .setPhone("139" + String.format("%08d", System.currentTimeMillis() % 100_000_000L))
                .setNickName(AUTHOR_NICK);
        userMapper.insert(author);
        userId = author.getId();

        UserDTO user = new UserDTO();
        user.setId(userId);
        user.setNickName(AUTHOR_NICK);
        UserHolder.saveUser(user);

        Event event = new Event()
                .setName("review-batch-test")
                .setCategoryId(1L)
                .setStartTime(LocalDateTime.now().plusDays(30))
                .setComments(0);
        eventMapper.insert(event);
        eventId = event.getId();
    }

    @AfterEach
    void tearDown() {
        for (Long reviewId : createdReviewIds) {
            stringRedisTemplate.delete(RedisConstants.REVIEW_LIKED_KEY + reviewId);
            eventReviewService.removeById(reviewId);
        }
        createdReviewIds.clear();
        if (eventId != null) {
            eventMapper.deleteById(eventId);
            eventId = null;
        }
        if (userId != null) {
            userMapper.deleteById(userId);
            userId = null;
        }
        UserHolder.removeUser();
    }

    private Long createReview(String title) {
        EventReviewCreateDTO dto = new EventReviewCreateDTO();
        dto.setEventId(eventId);
        dto.setTitle(title);
        dto.setContent("review-batch-test");
        Long id = eventReviewService.saveReview(dto);
        createdReviewIds.add(id);
        // 提高 liked 让测试评价稳定进入热门第一页，且不影响 isLike 对位断言。
        eventReviewService.update().setSql("liked = " + LIKED_BOOST).eq("id", id).update();
        return id;
    }

    @Test
    @DisplayName("1. 新增评价应累加 tb_event.comments")
    void testSaveReviewIncrementsEventComments() {
        Assertions.assertEquals(0, eventMapper.selectById(eventId).getComments(), "前置条件：初始评价数为 0");

        createReview("第一条");
        Assertions.assertEquals(1, eventMapper.selectById(eventId).getComments(), "存一条后应为 1");

        createReview("第二条");
        Assertions.assertEquals(2, eventMapper.selectById(eventId).getComments(), "存两条后应为 2");
    }

    @Test
    @DisplayName("2. 批量组装后作者信息与点赞状态仍逐条正确对位")
    void testBatchAssemblyKeepsPerRowAlignment() {
        // 只给中间评价点赞，验证 pipeline 结果与评价顺序对齐。
        Long first = createReview("A");
        Long second = createReview("B");
        Long third = createReview("C");
        stringRedisTemplate.opsForZSet()
                .add(RedisConstants.REVIEW_LIKED_KEY + second, userId.toString(), System.currentTimeMillis());

        List<EventReviewVO> voList = queryOwnReviews();

        Assertions.assertEquals(3, voList.size(), "应取回三条评价");
        for (EventReviewVO vo : voList) {
            Assertions.assertEquals(AUTHOR_NICK, vo.getUserName(), "作者昵称应由批量查询填上");
            Assertions.assertEquals(vo.getId().equals(second), vo.getIsLike(),
                    "评价 " + vo.getId() + " 的点赞状态对位错误");
        }
        Assertions.assertEquals(List.of(third, second, first), voList.stream().map(EventReviewVO::getId).toList(),
                "顺序应为 id 倒序");
    }

    @Test
    @DisplayName("3. 未登录时点赞状态全为 false，且不发 Redis 查询")
    void testNoCurrentUserYieldsAllFalse() {
        createReview("A");
        createReview("B");
        UserHolder.removeUser();

        List<EventReviewVO> voList = queryOwnReviews();

        Assertions.assertEquals(2, voList.size());
        for (EventReviewVO vo : voList) {
            Assertions.assertFalse(vo.getIsLike(), "未登录不应有点赞状态");
        }
    }

    @Test
    @DisplayName("4. 单条查询与列表查询给出一致的组装结果")
    void testSingleQueryMatchesListQuery() {
        // 单条路径复用批量实现，两条路径必须给出同样的字段
        Long id = createReview("A");
        stringRedisTemplate.opsForZSet()
                .add(RedisConstants.REVIEW_LIKED_KEY + id, userId.toString(), System.currentTimeMillis());

        EventReviewVO single = eventReviewService.queryReviewById(id);
        EventReviewVO fromList = queryOwnReviews().stream()
                .filter(vo -> vo.getId().equals(id))
                .findFirst()
                .orElseThrow();

        Assertions.assertEquals(single.getUserName(), fromList.getUserName());
        Assertions.assertEquals(single.getUserIcon(), fromList.getUserIcon());
        Assertions.assertEquals(single.getIsLike(), fromList.getIsLike());
        Assertions.assertTrue(single.getIsLike(), "已点赞的评价 isLike 应为 true");
        // 顺带钉住作者非空：两边都是 null 时上面几条也会通过，那样等于什么都没测
        Assertions.assertEquals(AUTHOR_NICK, single.getUserName(), "两条路径都应填上作者昵称");
    }

    @Test
    @DisplayName("5. 个人动态按发布时间分页且只返回该作者内容")
    void testQueryReviewsByUserIsPaged() {
        Long first = createReview("A");
        Long second = createReview("B");
        Long third = createReview("C");

        PageQuery query = new PageQuery();
        query.setSize(2);
        PageResult<EventReviewVO> firstPage = eventReviewService.queryReviewsByUser(userId, query);

        Assertions.assertEquals(3, firstPage.getTotal());
        Assertions.assertEquals(2, firstPage.getPages());
        Assertions.assertEquals(List.of(third, second),
                firstPage.getRecords().stream().map(EventReviewVO::getId).toList());
        Assertions.assertTrue(firstPage.getRecords().stream()
                .allMatch(review -> review.getUserId().equals(userId)));

        query.setCurrent(2);
        PageResult<EventReviewVO> secondPage = eventReviewService.queryReviewsByUser(userId, query);
        Assertions.assertEquals(List.of(first),
                secondPage.getRecords().stream().map(EventReviewVO::getId).toList());
    }

    // 通过热门评价接口覆盖批量路径。
    private List<EventReviewVO> queryOwnReviews() {
        PageQuery query = new PageQuery();
        query.setSize(100);
        return eventReviewService.queryHotReview(query).stream()
                .filter(vo -> createdReviewIds.contains(vo.getId()))
                .toList();
    }
}
