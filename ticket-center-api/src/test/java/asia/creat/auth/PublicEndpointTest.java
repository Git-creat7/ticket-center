package asia.creat.auth;

import asia.creat.service.EventService;
import asia.creat.support.IntegrationTestcontainers;
import asia.creat.utils.RedisConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 公开端点与匿名 UV 去重。
@SpringBootTest
@AutoConfigureMockMvc
public class PublicEndpointTest extends IntegrationTestcontainers {

    private static final long UV_EVENT_ID = 999_999_992L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EventService eventService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @AfterEach
    void cleanUp() {
        stringRedisTemplate.delete(RedisConstants.UV_EVENT_KEY + UV_EVENT_ID);
    }

    @Test
    @DisplayName("公开的只读接口仍然免登录")
    void testPublicReadEndpoints_StillAccessible() throws Exception {
        mockMvc.perform(get("/event/hot").param("current", "1")).andExpect(status().isOk());
        mockMvc.perform(get("/event/1")).andExpect(status().isOk());
        mockMvc.perform(get("/event-category/list")).andExpect(status().isOk());
        mockMvc.perform(get("/event-review/hot").param("current", "1")).andExpect(status().isOk());
        mockMvc.perform(get("/ticket/of/event/1")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("需登录的接口未登录时返回 401")
    void testProtectedEndpoints_RequireLogin() throws Exception {
        mockMvc.perform(get("/user/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/ticket-orders/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/event-review/like/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/event-review")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // GET 公开，写方法需要鉴权。
    @Test
    @DisplayName("同形状路径不会被整片放行")
    void testSameShapePaths_AreNotBlanketExcluded() throws Exception {
        mockMvc.perform(get("/event/1")).andExpect(status().isOk());
        mockMvc.perform(get("/user/1")).andExpect(status().isUnauthorized());
    }

    // 同一 IP 重复访问只计一个访客。
    @Test
    @DisplayName("同一 IP 反复上报 UV 只计一次")
    void testAddUv_SameIp_CountsOnce() {
        for (int i = 0; i < 50; i++) {
            eventService.addUv(UV_EVENT_ID, "203.0.113.7");
        }
        Assertions.assertEquals(1L, eventService.queryUv(UV_EVENT_ID),
                "同一来源 IP 应只计一个访客");
    }

    @Test
    @DisplayName("不同 IP 分别计数")
    void testAddUv_DifferentIps_CountSeparately() {
        eventService.addUv(UV_EVENT_ID, "203.0.113.7");
        eventService.addUv(UV_EVENT_ID, "203.0.113.8");
        eventService.addUv(UV_EVENT_ID, "203.0.113.9");
        Assertions.assertEquals(3L, eventService.queryUv(UV_EVENT_ID));
    }

    @Test
    @DisplayName("取不到来源 IP 时不计数，也不引入随机标识")
    void testAddUv_WithoutIp_DoesNotCount() {
        eventService.addUv(UV_EVENT_ID, null);
        eventService.addUv(UV_EVENT_ID, "  ");
        Assertions.assertEquals(0L, eventService.queryUv(UV_EVENT_ID));
    }

    // 游客可以上报 UV。
    @Test
    @DisplayName("PUT /event/uv 免登录且按请求 IP 去重")
    void testAddUvEndpoint_IsPublicAndDeduped() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(put("/event/uv/" + UV_EVENT_ID).with(req -> {
                req.setRemoteAddr("198.51.100.4");
                return req;
            })).andExpect(status().isOk());
        }
        Assertions.assertEquals(1L, eventService.queryUv(UV_EVENT_ID),
                "同一请求 IP 多次上报应只计一个访客");
    }
}
