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

import java.time.LocalDateTime;
import java.util.List;

import static asia.creat.utils.RedisConstants.CACHE_EVENT_DETAIL_KEY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
class EventDetailSerializationTest extends IntegrationTestcontainers {

    @Autowired
    private EventService eventService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("活动缓存往返不改变响应格式，票档由订单服务提供")
    void testEventDetailRoundtrip() throws Exception {
        String cacheKey = CACHE_EVENT_DETAIL_KEY + 1L;
        TicketVO ticket = new TicketVO();
        ticket.setId(1L);
        ticket.setPrice(10000L);
        ticket.setStock(2);
        ticket.setBeginTime(LocalDateTime.of(2026, 9, 1, 10, 0));
        ticket.setEndTime(LocalDateTime.of(2026, 9, 30, 18, 0));
        when(orderClient.queryTickets(1L)).thenReturn(List.of(ticket));
        stringRedisTemplate.delete(cacheKey);

        try {
            EventDetailVO fromDb = eventService.queryById(1L);
            String cachedJson = stringRedisTemplate.opsForValue().get(cacheKey);
            assertNotNull(cachedJson);
            assertFalse(objectMapper.readTree(cachedJson).hasNonNull("tickets"));

            EventDetailVO fromCache = eventService.queryById(1L);
            assertNotNull(fromDb.getStartTime());
            assertEquals(fromDb.getStartTime(), fromCache.getStartTime());
            assertEquals(List.of(ticket), fromCache.getTickets());
            String responseJson = objectMapper.writeValueAsString(fromCache);
            assertEquals(objectMapper.writeValueAsString(fromDb), responseJson);
            assertEquals("2026-09-01 10:00:00",
                    objectMapper.readTree(responseJson).path("tickets").get(0).path("beginTime").asText());
            verify(orderClient, times(2)).queryTickets(1L);
        } finally {
            stringRedisTemplate.delete(cacheKey);
        }
    }
}
