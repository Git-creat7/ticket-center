package asia.creat.profile;

import asia.creat.service.EventService;
import asia.creat.support.IntegrationTestcontainers;
import asia.creat.vo.EventDetailVO;
import asia.creat.vo.TicketVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static asia.creat.utils.RedisConstants.CACHE_EVENT_DETAIL_KEY;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class EventDetailSerializationTest extends IntegrationTestcontainers {

    @Autowired
    private EventService eventService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("验证 EventDetailVO 包含嵌套 List<TicketVO> 在 Hutool 缓存往返和 Jackson HTTP 序列化中的一致性")
    public void testEventDetailRoundtrip() throws Exception {
        Long eventId = 1L;
        String cacheKey = CACHE_EVENT_DETAIL_KEY + eventId;

        // 1. 强制回源（删除缓存）
        stringRedisTemplate.delete(cacheKey);

        // 2. 第一次查询（走 MySQL 组装，并写入 Redis 缓存）
        EventDetailVO dbVo = eventService.queryById(eventId);
        assertNotNull(dbVo, "DB 回源查询结果不应为空");
        String dbHttpJson = objectMapper.writeValueAsString(dbVo);

        // 3. 读取 Redis 原始 JSON 字符串
        String redisRawJson = stringRedisTemplate.opsForValue().get(cacheKey);
        assertNotNull(redisRawJson, "Redis 缓存应当已被写入");

        // 4. 第二次查询（命中 Redis 缓存，由 Hutool 反序列化）
        EventDetailVO cacheVo = eventService.queryById(eventId);
        assertNotNull(cacheVo, "缓存命中查询结果不应为空");
        String cacheHttpJson = objectMapper.writeValueAsString(cacheVo);

        // 5. 打印对比详细信息
        System.out.println("===============================================================");
        System.out.println(">>> [回源 DB 组装 VO (Jackson 序列化结果)]");
        System.out.println(dbHttpJson);
        System.out.println("---------------------------------------------------------------");
        System.out.println(">>> [Redis 中存储的 Hutool 序列化 Raw JSON]");
        System.out.println(redisRawJson);
        System.out.println("---------------------------------------------------------------");
        System.out.println(">>> [缓存命中 VO (Jackson 序列化结果)]");
        System.out.println(cacheHttpJson);
        System.out.println("===============================================================");

        // 6. 深入断言：顶层时间字段和缓存结构。
        assertEquals(dbVo.getStartTime(), cacheVo.getStartTime(), "演出 startTime 必须完全一致");

        // (2) 票档列表非空与泛型类型断言
        assertNotNull(dbVo.getTickets(), "DB 票档列表不应为空");
        assertNotNull(cacheVo.getTickets(), "缓存票档列表不应为空");
        assertEquals(dbVo.getTickets().size(), cacheVo.getTickets().size(), "票档数量必须一致");

        for (int i = 0; i < dbVo.getTickets().size(); i++) {
            TicketVO dbTicket = dbVo.getTickets().get(i);
            Object cacheTicketObj = cacheVo.getTickets().get(i);

            // 验证泛型是否未被擦除为 JSONObject/Map
            assertTrue(cacheTicketObj instanceof TicketVO,
                    "嵌套集合元素类型必须是 TicketVO，实际类型是: " + cacheTicketObj.getClass().getName());

            TicketVO cacheTicket = (TicketVO) cacheTicketObj;
            System.out.println("--- 票档 " + (i + 1) + " (" + cacheTicket.getTitle() + ") 时间校验 ---");
            System.out.println("  DB   beginTime: " + dbTicket.getBeginTime() + ", endTime: " + dbTicket.getEndTime());
            System.out.println("  Cache beginTime: " + cacheTicket.getBeginTime() + ", endTime: " + cacheTicket.getEndTime());

            assertEquals(dbTicket.getId(), cacheTicket.getId(), "票档 ID 一致");
            assertEquals(dbTicket.getPrice(), cacheTicket.getPrice(), "票档价格一致");
            assertEquals(dbTicket.getBeginTime(), cacheTicket.getBeginTime(), "票档 beginTime 必须完全一致");
            assertEquals(dbTicket.getEndTime(), cacheTicket.getEndTime(), "票档 endTime 必须完全一致");
        }

        // (3) 最终发给前端的 HTTP 响应体必须 100% 严格一致
        assertEquals(dbHttpJson, cacheHttpJson, "回源与缓存命中返回给前端的 JSON 字符串必须 100% 一致");
    }
}
