package asia.creat.config;

import asia.creat.entity.TicketOrder;
import asia.creat.entity.TicketStock;
import asia.creat.mapper.TicketOrderMapper;
import asia.creat.mapper.TicketStockMapper;
import asia.creat.utils.RedisConstants;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// 启动时补齐 Redis 预约资格和库存键。
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketStockCacheInitializer implements ApplicationRunner {

    private final TicketStockMapper ticketStockMapper;
    private final TicketOrderMapper ticketOrderMapper;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public void run(ApplicationArguments args) {
        // 顺序有意义：先补资格 Set，再写库存键。
        rebuildReservationSets();
        initializeStockKeys();
    }

    private void rebuildReservationSets() {
        // 只有待支付(0)与已出票(1)占用购票资格，已取消(2)不占
        List<TicketOrder> activeOrders = ticketOrderMapper.selectList(
                new LambdaQueryWrapper<TicketOrder>()
                        .select(TicketOrder::getTicketId, TicketOrder::getUserId)
                        .in(TicketOrder::getStatus, 0, 1));
        if (activeOrders.isEmpty()) {
            return;
        }

        Map<Long, List<String>> holdersByTicket = activeOrders.stream()
                .collect(Collectors.groupingBy(TicketOrder::getTicketId,
                        Collectors.mapping(order -> order.getUserId().toString(), Collectors.toList())));

        long restoredCount = 0;
        for (Map.Entry<Long, List<String>> entry : holdersByTicket.entrySet()) {
            String key = RedisConstants.ticketOrderKey(entry.getKey());
            Long added = stringRedisTemplate.opsForSet()
                    .add(key, entry.getValue().toArray(new String[0]));
            if (added != null) {
                restoredCount += added;
            }
        }

        // Redis 完好时 restoredCount 恒为 0；非 0 说明这次启动确实补回了丢失的资格
        log.info("Redis 一人一票资格检查完成，覆盖 {} 个票档，补回 {} 条资格记录",
                holdersByTicket.size(), restoredCount);
    }

    private void initializeStockKeys() {
        List<TicketStock> stocks = ticketStockMapper.selectList(null);
        int initializedCount = 0;

        for (TicketStock stock : stocks) {
            String key = RedisConstants.ticketStockKey(stock.getTicketId());
            Boolean initialized = stringRedisTemplate.opsForValue()
                    .setIfAbsent(key, stock.getStock().toString());
            if (Boolean.TRUE.equals(initialized)) {
                initializedCount++;
            }
        }

        log.info("Redis 票档库存检查完成，新初始化 {} 个库存键", initializedCount);
    }
}
