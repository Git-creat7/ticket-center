package asia.creat.config;

import asia.creat.entity.TicketOrder;
import asia.creat.entity.TicketReservation;
import asia.creat.entity.TicketStock;
import asia.creat.mapper.TicketOrderMapper;
import asia.creat.mapper.TicketReservationMapper;
import asia.creat.mapper.TicketStockMapper;
import asia.creat.utils.RedisConstants;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// 启动时补齐 Redis 预约资格和库存键。
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketStockCacheInitializer implements ApplicationRunner {

    private final TicketStockMapper ticketStockMapper;
    private final TicketOrderMapper ticketOrderMapper;
    private final TicketReservationMapper ticketReservationMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final TransactionTemplate transactionTemplate;

    private static final DefaultRedisScript<Long> INITIALIZE_SCRIPT = new DefaultRedisScript<>();

    static {
        INITIALIZE_SCRIPT.setLocation(new ClassPathResource("lua/initialize_ticket_stock.lua"));
        INITIALIZE_SCRIPT.setResultType(Long.class);
    }

    @Override
    public void run(ApplicationArguments args) {
        Set<Long> ticketIds = new HashSet<>();
        ticketStockMapper.selectList(null).forEach(stock -> ticketIds.add(stock.getTicketId()));
        List<TicketOrder> activeOrders = ticketOrderMapper.selectList(
                new LambdaQueryWrapper<TicketOrder>()
                        .select(TicketOrder::getTicketId)
                        .in(TicketOrder::getStatus, 0, 1));
        activeOrders.forEach(order -> ticketIds.add(order.getTicketId()));
        for (Long ticketId : ticketIds) {
            initialize(ticketId);
        }
        log.info("Redis 票档库存检查完成，覆盖 {} 个票档", ticketIds.size());
    }

    public void initialize(Long ticketId) {
        transactionTemplate.executeWithoutResult(status -> {
            // 和订单库存更新使用同一行锁，避免恢复过程中读到旧库存。
            TicketStock stock = ticketStockMapper.selectForUpdate(ticketId);
            List<TicketReservation> holders = ticketReservationMapper.selectActiveOrders(ticketId);
            List<String> args = new ArrayList<>();
            args.add(stock == null ? "" : stock.getStock().toString());
            for (TicketReservation holder : holders) {
                args.add(holder.getUserId().toString());
                args.add(holder.getId() == null ? "" : holder.getId().toString());
            }
            stringRedisTemplate.execute(INITIALIZE_SCRIPT,
                    List.of(RedisConstants.ticketStockKey(ticketId), RedisConstants.ticketOrderKey(ticketId),
                            RedisConstants.ticketReservationKey(ticketId)), args.toArray());
        });
    }
}
