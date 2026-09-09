package asia.creat.auth;

import asia.creat.service.CreditAccountService;
import asia.creat.support.IntegrationTestcontainers;
import asia.creat.utils.RedisConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class OrderBoundaryTest extends IntegrationTestcontainers {

    private static final long USER_ID = 88921L;
    private static final String TOKEN = "order-boundary-test";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private CreditAccountService creditAccountService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        redis.delete(RedisConstants.LOGIN_USER_KEY + TOKEN);
    }

    @Test
    void ticketsArePublicButOrdersAndSignRequireLogin() throws Exception {
        mockMvc.perform(get("/ticket/of/event/1")).andExpect(status().isOk());
        mockMvc.perform(get("/ticket-orders/me").header("X-User-Id", USER_ID))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/user/sign")).andExpect(status().isUnauthorized());
        verifyNoInteractions(coreClient);
    }

    @Test
    void ticketAdministrationChecksCurrentRoleFromCoreService() throws Exception {
        redis.opsForHash().putAll(RedisConstants.LOGIN_USER_KEY + TOKEN, Map.of("id", Long.toString(USER_ID)));
        when(coreClient.isAdmin(USER_ID)).thenReturn(false, true);
        mockMvc.perform(post("/ticket").header("authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/ticket").header("authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(400));
        verify(coreClient, times(2)).isAdmin(USER_ID);
    }

    @Test
    void internalReadNeedsCredentialAndDoesNotCreateAccount() throws Exception {
        mockMvc.perform(get("/internal/credits/" + USER_ID)).andExpect(status().isForbidden());
        mockMvc.perform(get("/internal/credits/" + USER_ID)
                        .header("X-Ticket-Internal-Token", "test-internal-token"))
                .andExpect(status().isOk()).andExpect(content().string("0"));
        assertNull(creditAccountService.getById(USER_ID));
    }

    @Test
    void orderDatabaseDoesNotContainCoreTables() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = DATABASE() AND table_name IN ('tb_user', 'tb_user_info', 'tb_event')", Integer.class);
        assertEquals(0, count);
    }
}
