package asia.creat.profile;

import asia.creat.support.IntegrationTestcontainers;
import asia.creat.service.EventService;
import asia.creat.vo.TicketVO;
import feign.FeignException;
import feign.Request;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static asia.creat.utils.RedisConstants.CACHE_EVENT_DETAIL_KEY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class EventDetailAggregationTest extends IntegrationTestcontainers {

    @Autowired
    private EventService eventService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private MockMvc mockMvc;

    @Test
    void cachedEventStillLoadsCurrentStock() {
        stringRedisTemplate.delete(CACHE_EVENT_DETAIL_KEY + 1L);
        TicketVO available = new TicketVO();
        available.setId(1L);
        available.setStock(1);
        TicketVO soldOut = new TicketVO();
        soldOut.setId(1L);
        soldOut.setStock(0);
        soldOut.setHasWaitlist(true);
        when(orderClient.queryTickets(1L)).thenReturn(List.of(available), List.of(soldOut));

        assertEquals(1, eventService.queryById(1L).getTickets().get(0).getStock());
        var current = eventService.queryById(1L);
        assertEquals(0, current.getTickets().get(0).getStock());
        assertTrue(current.getTickets().get(0).getHasWaitlist());
        verify(orderClient, times(2)).queryTickets(1L);
        assertFalse(stringRedisTemplate.opsForValue().get(CACHE_EVENT_DETAIL_KEY + 1L).contains("\"stock\""));
    }

    @Test
    void unavailableOrderServiceDoesNotPretendTicketsAreSoldOut() throws Exception {
        Request request = Request.create(Request.HttpMethod.GET, "http://order/internal/tickets/of/event/1",
                Map.of(), null, StandardCharsets.UTF_8, null);
        when(orderClient.queryTickets(1L)).thenThrow(
                new FeignException.ServiceUnavailable("unavailable", request, null, Map.of()));

        mockMvc.perform(get("/event/1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503));
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
