package asia.creat.profile;

import asia.creat.support.IntegrationTestcontainers;
import asia.creat.service.EventService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static asia.creat.utils.RedisConstants.CACHE_EVENT_DETAIL_KEY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class EventDetailQueryTest extends IntegrationTestcontainers {

    @Autowired
    private EventService eventService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private MockMvc mockMvc;

    @Test
    void eventDetailDoesNotQueryOrderService() {
        String cacheKey = CACHE_EVENT_DETAIL_KEY + 1L;
        stringRedisTemplate.delete(cacheKey);
        try {
            var fromDb = eventService.queryById(1L);
            assertNotNull(stringRedisTemplate.opsForValue().get(cacheKey));
            assertEquals(fromDb, eventService.queryById(1L));
            verifyNoInteractions(orderClient);
        } finally {
            stringRedisTemplate.delete(cacheKey);
        }
    }

    @Test
    void eventDetailOnlyReturnsEventMetadata() throws Exception {
        mockMvc.perform(get("/event/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.tickets").doesNotExist());
        verifyNoInteractions(orderClient);
    }

    @Test
    void missingEventStillReturnsNotFound() throws Exception {
        mockMvc.perform(get("/event/999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
        verifyNoInteractions(orderClient);
    }

    @Test
    void internalEventQueryRequiresServiceCredential() throws Exception {
        mockMvc.perform(get("/internal/events/1")).andExpect(status().isForbidden());
        mockMvc.perform(get("/internal/events/1").header("X-Ticket-Internal-Token", "wrong"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/internal/events/1").header("X-Ticket-Internal-Token", "test-internal-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(1));
    }
}
