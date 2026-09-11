package asia.creat.profile;

import asia.creat.service.EventService;
import asia.creat.support.IntegrationTestcontainers;
import asia.creat.vo.EventDetailVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.format.DateTimeFormatter;

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
    @DisplayName("活动缓存往返不改变响应格式，不包含实时票档")
    void testEventDetailRoundtrip() throws Exception {
        String cacheKey = CACHE_EVENT_DETAIL_KEY + 1L;
        stringRedisTemplate.delete(cacheKey);

        try {
            EventDetailVO fromDb = eventService.queryById(1L);
            String cachedJson = stringRedisTemplate.opsForValue().get(cacheKey);
            assertNotNull(cachedJson);
            assertFalse(objectMapper.readTree(cachedJson).hasNonNull("tickets"));

            EventDetailVO fromCache = eventService.queryById(1L);
            assertNotNull(fromDb.getStartTime());
            assertEquals(fromDb.getStartTime(), fromCache.getStartTime());
            String responseJson = objectMapper.writeValueAsString(fromCache);
            assertEquals(objectMapper.writeValueAsString(fromDb), responseJson);
            assertFalse(objectMapper.readTree(responseJson).has("tickets"));
            assertEquals(fromDb.getStartTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                    objectMapper.readTree(responseJson).path("startTime").asText());
            verifyNoInteractions(orderClient);
        } finally {
            stringRedisTemplate.delete(cacheKey);
        }
    }
}
