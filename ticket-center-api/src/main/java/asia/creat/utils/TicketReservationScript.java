package asia.creat.utils;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class TicketReservationScript {

    private final StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> RESERVATION_SCRIPT;
    private static final DefaultRedisScript<Long> RESERVATION_ROLLBACK_SCRIPT;

    static {
        RESERVATION_SCRIPT = new DefaultRedisScript<>();
        RESERVATION_SCRIPT.setLocation(new ClassPathResource("lua/reserve_ticket.lua"));
        RESERVATION_SCRIPT.setResultType(Long.class);

        RESERVATION_ROLLBACK_SCRIPT = new DefaultRedisScript<>();
        RESERVATION_ROLLBACK_SCRIPT.setLocation(new ClassPathResource("lua/reserve_ticket_rollback.lua"));
        RESERVATION_ROLLBACK_SCRIPT.setResultType(Long.class);
    }

    public Long reserve(Long ticketId, Long userId) {
        return stringRedisTemplate.execute(RESERVATION_SCRIPT, keys(ticketId), userId.toString());
    }

    public Long rollback(Long ticketId, Long userId) {
        return stringRedisTemplate.execute(RESERVATION_ROLLBACK_SCRIPT, keys(ticketId), userId.toString());
    }

    private List<String> keys(Long ticketId) {
        return List.of(RedisConstants.ticketStockKey(ticketId), RedisConstants.ticketOrderKey(ticketId));
    }
}
