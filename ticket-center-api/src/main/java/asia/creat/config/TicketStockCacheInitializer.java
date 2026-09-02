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

// 启动时把 Redis 里预约链路依赖的两份状态对齐回数据库：票档库存与一人一票资格 Set
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
        // 库存键不存在时 Lua 第一步就返回“库存不足”，所以库存没写之前谁都抢不到票；
        // 反过来先放开库存，资格 Set 还没补完的这段时间里已持票用户能再抢一张。
        rebuildReservationSets();
        initializeStockKeys();
    }

    /**
     * 重建一人一票资格 Set。
     *
     * 库存键有 setIfAbsent 兜着，资格 Set 一直没人管：Redis flush、主从切换丢数据、
     * key 格式迁移之后 Set 是空的，已持有活跃订单的用户能再次通过 Lua 里的 sismember。
     * 超卖仍被 MySQL 的 stock > 0 挡住，丢的是一人一票这条规则——用户预约拿到订单号、
     * 页面显示成功，落库时被 activeCount 检查拒掉，走完重试进死信才回滚这次预扣。
     * <p>
     * 用 SADD 而不是库存那种“空了才写”：SADD 只增不减、天然幂等，对运行中的系统是安全的
     * 补齐（活跃订单本来就该在 Set 里）。库存键不能这么干，那是计数器，无条件覆盖会把
     * 正在预扣的数字冲掉。
     */
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
