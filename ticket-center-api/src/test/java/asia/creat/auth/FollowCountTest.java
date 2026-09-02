package asia.creat.auth;

import asia.creat.common.exception.BusinessException;
import asia.creat.dto.UserDTO;
import asia.creat.entity.Follow;
import asia.creat.entity.User;
import asia.creat.mapper.FollowMapper;
import asia.creat.mapper.UserMapper;
import asia.creat.service.FollowService;
import asia.creat.utils.RedisConstants;
import asia.creat.utils.UserHolder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 关注数与粉丝数（第 16 条）。
 *
 * tb_user_info.fans / followee 只在建行时写 0，关注与取关从不维护，
 * 而两个页面都把它们当实时计数展示，于是永远显示 0。
 */
@SpringBootTest
@AutoConfigureMockMvc
public class FollowCountTest {

    private static final Long ACTOR = 88_831L;
    private static final List<Long> TARGETS = List.of(88_832L, 88_833L, 88_834L);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FollowService followService;

    @Autowired
    private FollowMapper followMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private String token;

    /**
     * 被关注方也必须真的落库。
     *
     * 原来只建了 ACTOR，TARGETS 从没插过 —— 用例能过是因为 follow() 当时不校验目标存在性，
     * 关注三个虚构 id 也照样写库。补上存在性校验后这里就会抛「目标用户不存在」。
     * cleanUp 里本来就在 deleteById(target)，不用另外清。
     */
    @BeforeEach
    void setUp() {
        TARGETS.forEach(this::insertUser);
    }

    @AfterEach
    void cleanUp() {
        followMapper.delete(new LambdaQueryWrapper<Follow>().eq(Follow::getUserId, ACTOR));
        for (Long target : TARGETS) {
            followMapper.delete(new LambdaQueryWrapper<Follow>().eq(Follow::getFollowUserId, target));
            userMapper.deleteById(target);
        }
        userMapper.deleteById(ACTOR);
        stringRedisTemplate.delete(RedisConstants.FEED_KEY + ACTOR);
        if (token != null) {
            stringRedisTemplate.delete(RedisConstants.LOGIN_USER_KEY + token);
        }
        UserHolder.removeUser();
    }

    @Test
    @DisplayName("关注三个人后，/user/info 的 followee 应为 3 而不是库里那个 0")
    void testFolloweeCount_ReflectsActualFollows() throws Exception {
        login(ACTOR);
        TARGETS.forEach(target -> followService.follow(target, true));

        mockMvc.perform(get("/user/info/" + ACTOR).header("authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.followee").value(TARGETS.size()));
    }

    @Test
    @DisplayName("被关注方的 fans 应随之增加，取关后回落")
    void testFansCount_ReflectsActualFollowers() throws Exception {
        Long target = TARGETS.get(0);
        login(ACTOR);
        followService.follow(target, true);

        mockMvc.perform(get("/user/info/" + target).header("authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fans").value(1));

        // MockMvc 请求走完拦截器链，afterCompletion 会清掉 UserHolder，
        // 直接调 service 前要重新放回当前用户
        holdUser(ACTOR);
        followService.follow(target, false);

        mockMvc.perform(get("/user/info/" + target).header("authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fans").value(0));
    }

    @Test
    @DisplayName("关注和粉丝列表支持分页查看")
    void testFollowListsArePaged() throws Exception {
        login(ACTOR);
        TARGETS.forEach(target -> followService.follow(target, true));

        mockMvc.perform(get("/follow/followees/" + ACTOR)
                        .header("authorization", token)
                        .param("current", "2")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.current").value(2))
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(TARGETS.get(0)));

        mockMvc.perform(get("/follow/fans/" + TARGETS.get(0))
                        .header("authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(ACTOR));
    }

    @Test
    @DisplayName("关注不存在的用户应被拒绝，不留下幽灵关注")
    void testFollowRejectsNonexistentTarget() {
        Long ghost = 88_899L;
        userMapper.deleteById(ghost);
        login(ACTOR);

        BusinessException e = Assertions.assertThrows(BusinessException.class,
                () -> followService.follow(ghost, true));
        Assertions.assertEquals(404, e.getCode());

        // 关键断言：MySQL 不该留痕
        Assertions.assertEquals(0, followMapper.selectCount(
                new LambdaQueryWrapper<Follow>().eq(Follow::getFollowUserId, ghost)));
    }

    /** 插入用户、写入 token 会话，并设置 UserHolder 以便直接调用 service 层 */
    private void login(Long userId) {
        insertUser(userId);

        token = UUID.randomUUID().toString();
        stringRedisTemplate.opsForHash().putAll(
                RedisConstants.LOGIN_USER_KEY + token,
                Map.of("id", String.valueOf(userId), "nickName", "follow-test-" + userId));

        holdUser(userId);
    }

    /** 先删后插，避免上次跑崩留下的残留撞唯一索引 */
    private void insertUser(Long userId) {
        userMapper.deleteById(userId);
        User user = new User();
        user.setId(userId);
        user.setPhone("139" + String.format("%08d", userId % 100_000_000L));
        user.setNickName("follow-test-" + userId);
        userMapper.insert(user);
    }

    private void holdUser(Long userId) {
        UserDTO dto = new UserDTO();
        dto.setId(userId);
        UserHolder.saveUser(dto);
    }
}
