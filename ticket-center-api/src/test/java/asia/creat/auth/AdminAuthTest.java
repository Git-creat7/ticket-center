package asia.creat.auth;

import asia.creat.entity.User;
import asia.creat.mapper.UserMapper;
import asia.creat.support.IntegrationTestcontainers;
import asia.creat.utils.RedisConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 管理接口的权限校验。
@SpringBootTest
@AutoConfigureMockMvc
public class AdminAuthTest extends IntegrationTestcontainers {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private Long createdUserId;
    private String createdTokenKey;

    @AfterEach
    void cleanUp() {
        if (createdTokenKey != null) {
            stringRedisTemplate.delete(createdTokenKey);
            createdTokenKey = null;
        }
        if (createdUserId != null) {
            userMapper.deleteById(createdUserId);
            createdUserId = null;
        }
    }

    /** 建一个指定角色的用户并签发登录态，返回可用的 token。 */
    private String loginAs(int role) {
        User user = new User()
                .setPhone("139" + String.format("%08d", (System.nanoTime() % 100_000_000L)))
                .setPassword("")
                .setNickName("auth-test")
                .setRole(role);
        userMapper.insert(user);
        createdUserId = user.getId();

        String token = UUID.randomUUID().toString().replace("-", "");
        createdTokenKey = RedisConstants.LOGIN_USER_KEY + token;
        Map<String, String> userMap = new HashMap<>();
        userMap.put("id", String.valueOf(user.getId()));
        userMap.put("nickName", user.getNickName());
        userMap.put("icon", "");
        stringRedisTemplate.opsForHash().putAll(createdTokenKey, userMap);
        stringRedisTemplate.expire(createdTokenKey, RedisConstants.LOGIN_USER_TTL);
        return token;
    }

    @Test
    @DisplayName("普通用户创建活动应返回 403")
    void testCreateEvent_AsNormalUser_ShouldBeForbidden() throws Exception {
        String token = loginAs(0);

        mockMvc.perform(post("/event")
                        .header("authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                // 状态码之外还要有统一错误体：原先 body 是空的，调用方只能看到裸 403
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("需要管理员权限"));
    }

    @Test
    @DisplayName("普通用户新增票档应返回 403")
    void testAddTicket_AsNormalUser_ShouldBeForbidden() throws Exception {
        String token = loginAs(0);

        mockMvc.perform(post("/ticket")
                        .header("authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("未登录创建活动应返回 401，且不被误判成 403")
    void testCreateEvent_WithoutLogin_ShouldBeUnauthorized() throws Exception {
        mockMvc.perform(post("/event")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.msg").value("未登录或登录已过期"));
    }

    // 管理员应通过拦截器并进入参数校验。
    @Test
    @DisplayName("管理员创建活动应穿过权限校验，落到参数校验")
    void testCreateEvent_AsAdmin_ShouldPassAuthorization() throws Exception {
        String token = loginAs(1);

        mockMvc.perform(post("/event")
                        .header("authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    // 同一路径上的 GET 保持公开。
    @Test
    @DisplayName("公开的 GET /event/{id} 不受管理员校验影响")
    void testQueryEvent_IsStillPublic() throws Exception {
        mockMvc.perform(get("/event/1"))
                .andExpect(status().isOk());
    }
}
