package asia.creat.utils;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 票档预约的 Redis 原子脚本入口：统一持有预扣与回滚两个 Lua 脚本及其键构造。
 *
 * 返回值保持 Lua 原始语义，由调用方按各自场景处理：
 * - reserve：0 成功、1 库存不足、2 已限购；
 * - rollback：1 回滚成功，其余表示预约记录不存在或已回退。
 */
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

    /**
     * 原子完成：检查库存、检查一人一票、扣减库存
     */
    public Long reserve(Long ticketId, Long userId) {
        return stringRedisTemplate.execute(RESERVATION_SCRIPT, keys(ticketId), userId.toString());
    }

    /**
     * 原子回滚预扣库存与购买资格
     */
    public Long rollback(Long ticketId, Long userId) {
        return stringRedisTemplate.execute(RESERVATION_ROLLBACK_SCRIPT, keys(ticketId), userId.toString());
    }

    private List<String> keys(Long ticketId) {
        return List.of(RedisConstants.ticketStockKey(ticketId), RedisConstants.ticketOrderKey(ticketId));
    }
}
