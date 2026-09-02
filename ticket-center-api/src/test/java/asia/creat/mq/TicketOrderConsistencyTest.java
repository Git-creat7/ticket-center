package asia.creat.mq;

import asia.creat.common.exception.BusinessException;
import asia.creat.config.TicketStockCacheInitializer;
import asia.creat.dto.TicketOrderCancelMessage;
import asia.creat.dto.TicketOrderMessage;
import asia.creat.entity.Ticket;
import asia.creat.entity.TicketOrder;
import asia.creat.entity.TicketStock;
import asia.creat.mapper.TicketMapper;
import asia.creat.mapper.TicketOrderMapper;
import asia.creat.mapper.TicketStockMapper;
import asia.creat.service.TicketOrderService;
import asia.creat.support.IntegrationTestcontainers;
import asia.creat.utils.TicketReservationScript;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;

@SpringBootTest
public class TicketOrderConsistencyTest extends IntegrationTestcontainers {

    @Autowired
    private TicketOrderDeadConsumer ticketOrderDeadConsumer;

    @Autowired
    private TicketOrderCancelConsumer ticketOrderCancelConsumer;

    @Autowired
    private TicketOrderService ticketOrderService;

    @Autowired
    private TicketOrderMapper ticketOrderMapper;

    @Autowired
    private TicketStockMapper ticketStockMapper;

    @Autowired
    private TicketMapper ticketMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private TicketStockCacheInitializer cacheInitializer;

    @Autowired
    private TicketReservationScript ticketReservationScript;

    @Test
    @DisplayName("1. 测试死信消费者防御：MySQL已落库时，严禁误回滚Redis库存与资格")
    void testDeadConsumer_WhenOrderExistsInDb_ShouldNotRollbackRedis() {
        Long testTicketId = 99901L;
        Long testUserId = 88801L;
        Long testOrderId = 77701L;

        String stockKey = "tc:ticket:{" + testTicketId + "}:stock";
        String orderKey = "tc:ticket:{" + testTicketId + "}:order";

        // 模拟 Redis 当前状态：库存已被扣为 10，用户在资格 Set 中
        stringRedisTemplate.opsForValue().set(stockKey, "10");
        stringRedisTemplate.opsForSet().add(orderKey, testUserId.toString());

        // 模拟 MySQL 中订单已成功落库
        ticketOrderMapper.deleteById(testOrderId);
        TicketOrder order = new TicketOrder();
        order.setId(testOrderId);
        order.setUserId(testUserId);
        order.setTicketId(testTicketId);
        order.setEventId(1L);
        order.setPrice(100L);
        order.setStatus(0); // 待支付
        ticketOrderMapper.insert(order);

        try {
            // 模拟死信消息到达
            TicketOrderMessage deadMessage = new TicketOrderMessage();
            deadMessage.setId(testOrderId);
            deadMessage.setUserId(testUserId);
            deadMessage.setTicketId(testTicketId);

            ticketOrderDeadConsumer.receiveDeadLetter(deadMessage);

            // 验证：Redis 库存仍然是 10（没有被误加成 11），用户仍然在 Set 中（没有被误删除）
            String currentStock = stringRedisTemplate.opsForValue().get(stockKey);
            Boolean isMember = stringRedisTemplate.opsForSet().isMember(orderKey, testUserId.toString());

            Assertions.assertEquals("10", currentStock, "MySQL 已落库时，死信消费者不应增加 Redis 库存");
            Assertions.assertTrue(Boolean.TRUE.equals(isMember), "MySQL 已落库时，死信消费者不应清除一人一票资格");
        } finally {
            // 清理测试数据
            ticketOrderMapper.deleteById(testOrderId);
            stringRedisTemplate.delete(stockKey);
            stringRedisTemplate.delete(orderKey);
        }
    }

    @Test
    @DisplayName("2. 测试死信消费者补偿：MySQL未落库时，正常回滚Redis库存与一人一票资格")
    void testDeadConsumer_WhenOrderNotExistsInDb_ShouldRollbackRedis() {
        Long testTicketId = 99902L;
        Long testUserId = 88802L;
        Long testOrderId = 77702L;

        String stockKey = "tc:ticket:{" + testTicketId + "}:stock";
        String orderKey = "tc:ticket:{" + testTicketId + "}:order";

        // 模拟 Redis 当前状态：库存 10，用户在资格 Set 中
        stringRedisTemplate.opsForValue().set(stockKey, "10");
        stringRedisTemplate.opsForSet().add(orderKey, testUserId.toString());

        // 确保 MySQL 中不存在该订单（模拟落库失败）
        ticketOrderMapper.deleteById(testOrderId);

        try {
            TicketOrderMessage deadMessage = new TicketOrderMessage();
            deadMessage.setId(testOrderId);
            deadMessage.setUserId(testUserId);
            deadMessage.setTicketId(testTicketId);

            ticketOrderDeadConsumer.receiveDeadLetter(deadMessage);

            // 验证：Redis 库存被回补为 11，用户资格被成功清除
            String currentStock = stringRedisTemplate.opsForValue().get(stockKey);
            Boolean isMember = stringRedisTemplate.opsForSet().isMember(orderKey, testUserId.toString());

            Assertions.assertEquals("11", currentStock, "MySQL 未落库时，死信消费者应将 Redis 库存回补 +1");
            Assertions.assertFalse(Boolean.TRUE.equals(isMember), "MySQL 未落库时，死信消费者应清除一人一票资格");
        } finally {
            stringRedisTemplate.delete(stockKey);
            stringRedisTemplate.delete(orderKey);
        }
    }

    @Test
    @DisplayName("3. 测试延时关单：待支付订单超时，流转为已取消并释放 MySQL 与 Redis 库存")
    void testCancelConsumer_WhenOrderPending_ShouldCancelAndReleaseStock() {
        Long testTicketId = 1L; // 数据库中已有票档
        Long testUserId = 88803L;
        Long testOrderId = 77703L;

        String stockKey = "tc:ticket:{" + testTicketId + "}:stock";
        String orderKey = "tc:ticket:{" + testTicketId + "}:order";

        // 准备 Redis 状态
        stringRedisTemplate.opsForValue().set(stockKey, "50");
        stringRedisTemplate.opsForSet().add(orderKey, testUserId.toString());

        // 准备 MySQL 库存记录基准
        TicketStock initialStock = ticketStockMapper.selectById(testTicketId);
        int baseStock = initialStock.getStock();

        // 准备 MySQL 待支付订单
        ticketOrderMapper.deleteById(testOrderId);
        TicketOrder order = new TicketOrder();
        order.setId(testOrderId);
        order.setUserId(testUserId);
        order.setTicketId(testTicketId);
        order.setEventId(1L);
        order.setPrice(100L);
        order.setStatus(0); // 待支付
        ticketOrderMapper.insert(order);

        try {
            // 模拟延时关单消息到达
            TicketOrderCancelMessage cancelMessage = TicketOrderCancelMessage.builder()
                    .orderId(testOrderId)
                    .userId(testUserId)
                    .ticketId(testTicketId)
                    .timestamp(System.currentTimeMillis())
                    .build();

            ticketOrderCancelConsumer.handleCancelOrder(cancelMessage);

            // 验证 1：MySQL 订单状态流转为 2 (已取消)
            TicketOrder updatedOrder = ticketOrderMapper.selectById(testOrderId);
            Assertions.assertEquals(2, updatedOrder.getStatus(), "超时订单应流转为已取消 (status=2)");

            // 验证 2：MySQL 库存回补 +1
            TicketStock updatedStock = ticketStockMapper.selectById(testTicketId);
            Assertions.assertEquals(baseStock + 1, updatedStock.getStock(), "MySQL 库存应回补 +1");

            // 验证 3：Redis 库存回补为 51，且一人一票资格已释放
            String currentStock = stringRedisTemplate.opsForValue().get(stockKey);
            Boolean isMember = stringRedisTemplate.opsForSet().isMember(orderKey, testUserId.toString());
            Assertions.assertEquals("51", currentStock, "Redis 库存应回补 +1");
            Assertions.assertFalse(Boolean.TRUE.equals(isMember), "Redis 一人一票资格应被释放");

        } finally {
            // 还原 MySQL 库存与清理测试订单
            initialStock.setStock(baseStock);
            ticketStockMapper.updateById(initialStock);
            ticketOrderMapper.deleteById(testOrderId);
            stringRedisTemplate.delete(stockKey);
            stringRedisTemplate.delete(orderKey);
        }
    }

    @Test
    @DisplayName("4. 测试延时关单幂等性：已支付订单超时消息到达，不应误取消或重复加库存")
    void testCancelConsumer_WhenOrderPaid_ShouldIgnore() {
        Long testTicketId = 1L;
        Long testUserId = 88804L;
        Long testOrderId = 77704L;

        TicketStock initialStock = ticketStockMapper.selectById(testTicketId);
        int baseStock = initialStock.getStock();

        // 准备 MySQL 已出票/已支付订单
        ticketOrderMapper.deleteById(testOrderId);
        TicketOrder order = new TicketOrder();
        order.setId(testOrderId);
        order.setUserId(testUserId);
        order.setTicketId(testTicketId);
        order.setEventId(1L);
        order.setPrice(100L);
        order.setStatus(1); // 已出票/已支付
        order.setPayTime(LocalDateTime.now());
        ticketOrderMapper.insert(order);

        try {
            TicketOrderCancelMessage cancelMessage = TicketOrderCancelMessage.builder()
                    .orderId(testOrderId)
                    .userId(testUserId)
                    .ticketId(testTicketId)
                    .timestamp(System.currentTimeMillis())
                    .build();

            ticketOrderCancelConsumer.handleCancelOrder(cancelMessage);

            // 验证：MySQL 订单状态仍为 1，库存没有多加
            TicketOrder currentOrder = ticketOrderMapper.selectById(testOrderId);
            Assertions.assertEquals(1, currentOrder.getStatus(), "已支付订单不应被取消");

            TicketStock currentStock = ticketStockMapper.selectById(testTicketId);
            Assertions.assertEquals(baseStock, currentStock.getStock(), "已支付订单不应多回补库存");
        } finally {
            ticketOrderMapper.deleteById(testOrderId);
        }
    }

    @Test
    @DisplayName("5. 测试库存回补后失效详情聚合缓存：否则前端最长 30 分钟看到旧库存")
    void testReleaseStock_ShouldEvictEventDetailCache() {
        Long testTicketId = 1L;
        Long testUserId = 88805L;
        Long testOrderId = 77705L;
        Long testEventId = 1L;

        String stockKey = "tc:ticket:{" + testTicketId + "}:stock";
        String orderKey = "tc:ticket:{" + testTicketId + "}:order";
        String detailCacheKey = "tc:cache:event:detail:" + testEventId;

        stringRedisTemplate.opsForValue().set(stockKey, "50");
        stringRedisTemplate.opsForSet().add(orderKey, testUserId.toString());

        // 放一个哨兵值占住详情缓存：EventDetailVO 里带着票档 stock，
        // 库存变动后若不失效，这份旧数据会一直服务到 TTL 到期
        stringRedisTemplate.opsForValue().set(detailCacheKey, "{\"sentinel\":true}");

        TicketStock initialStock = ticketStockMapper.selectById(testTicketId);
        int baseStock = initialStock.getStock();

        ticketOrderMapper.deleteById(testOrderId);
        TicketOrder order = new TicketOrder();
        order.setId(testOrderId);
        order.setUserId(testUserId);
        order.setTicketId(testTicketId);
        order.setEventId(testEventId);
        order.setPrice(100L);
        order.setStatus(0);
        ticketOrderMapper.insert(order);

        try {
            Assertions.assertTrue(stringRedisTemplate.hasKey(detailCacheKey), "前置条件：哨兵缓存应存在");

            ticketOrderCancelConsumer.handleCancelOrder(TicketOrderCancelMessage.builder()
                    .orderId(testOrderId)
                    .userId(testUserId)
                    .ticketId(testTicketId)
                    .timestamp(System.currentTimeMillis())
                    .build());

            // 库存确实回补了，说明走到了 releaseStock
            TicketStock updatedStock = ticketStockMapper.selectById(testTicketId);
            Assertions.assertEquals(baseStock + 1, updatedStock.getStock(), "MySQL 库存应回补 +1");

            // 缓存必须已被删除。删除挂在事务提交之后，此处已提交完毕
            Assertions.assertFalse(stringRedisTemplate.hasKey(detailCacheKey),
                    "库存回补后详情聚合缓存应被失效，否则前端继续读到旧库存");

        } finally {
            initialStock.setStock(baseStock);
            ticketStockMapper.updateById(initialStock);
            ticketOrderMapper.deleteById(testOrderId);
            stringRedisTemplate.delete(stockKey);
            stringRedisTemplate.delete(orderKey);
            stringRedisTemplate.delete(detailCacheKey);
        }
    }

    @Test
    @DisplayName("6. 测试落库幂等键：订单取消后消息重投，不应再扣一次库存")
    void testCreateOrder_WhenMessageRedeliveredAfterCancel_ShouldBeIdempotent() {
        Long testTicketId = 1L;
        Long testUserId = 88806L;
        Long testOrderId = 77706L;

        TicketStock initialStock = ticketStockMapper.selectById(testTicketId);
        int baseStock = initialStock.getStock();

        // 已取消的订单：幂等键若用“人 + 票有没有活跃订单”，status=2 不在 (0,1) 里，
        // 这条重投的消息会被当成新单继续处理，白扣一次库存后才在主键冲突上失败
        ticketOrderMapper.deleteById(testOrderId);
        TicketOrder cancelled = new TicketOrder();
        cancelled.setId(testOrderId);
        cancelled.setUserId(testUserId);
        cancelled.setTicketId(testTicketId);
        cancelled.setEventId(1L);
        cancelled.setPrice(100L);
        cancelled.setStatus(2);
        ticketOrderMapper.insert(cancelled);

        try {
            TicketOrderMessage message = new TicketOrderMessage();
            message.setId(testOrderId);
            message.setUserId(testUserId);
            message.setTicketId(testTicketId);
            message.setUseCredits(false);

            // 幂等键是订单号，应当直接返回，不抛异常
            Assertions.assertDoesNotThrow(() -> ticketOrderService.createTicketOrder(message),
                    "重复投递的消息应被幂等跳过");

            TicketStock afterStock = ticketStockMapper.selectById(testTicketId);
            Assertions.assertEquals(baseStock, afterStock.getStock(),
                    "重复投递不应扣减库存");

            TicketOrder stillCancelled = ticketOrderMapper.selectById(testOrderId);
            Assertions.assertEquals(2, stillCancelled.getStatus(),
                    "已取消的订单状态不应被重投的消息改写");

        } finally {
            initialStock.setStock(baseStock);
            ticketStockMapper.updateById(initialStock);
            ticketOrderMapper.deleteById(testOrderId);
        }
    }

    @Test
    @DisplayName("7. 测试一人一票业务规则：已有活跃订单时新单必须抛出，交由死信回滚泄漏的预约")
    void testCreateOrder_WhenUserAlreadyHasActiveOrder_ShouldThrow() {
        Long testTicketId = 1L;
        Long testUserId = 88807L;
        Long existingOrderId = 77707L;
        Long newOrderId = 77708L;

        TicketStock initialStock = ticketStockMapper.selectById(testTicketId);
        int baseStock = initialStock.getStock();

        // 已有一张待支付订单
        ticketOrderMapper.deleteById(existingOrderId);
        ticketOrderMapper.deleteById(newOrderId);
        TicketOrder active = new TicketOrder();
        active.setId(existingOrderId);
        active.setUserId(testUserId);
        active.setTicketId(testTicketId);
        active.setEventId(1L);
        active.setPrice(100L);
        active.setStatus(0);
        ticketOrderMapper.insert(active);

        try {
            // 另一个订单号的消息到达：说明 Redis 的一人一票 Set 与 MySQL 不一致
            // （Redis 掉过数据、预热未重建资格 Set 等）。必须抛出，让重试耗尽后进死信，
            // 由死信消费者回滚这次预约，否则 Redis 里的预扣永久泄漏
            TicketOrderMessage message = new TicketOrderMessage();
            message.setId(newOrderId);
            message.setUserId(testUserId);
            message.setTicketId(testTicketId);
            message.setUseCredits(false);

            Assertions.assertThrows(BusinessException.class,
                    () -> ticketOrderService.createTicketOrder(message),
                    "已持有活跃订单时不应静默返回，否则 Redis 预扣泄漏无人回滚");

            Assertions.assertNull(ticketOrderMapper.selectById(newOrderId),
                    "被拒的新单不应落库");

            TicketStock afterStock = ticketStockMapper.selectById(testTicketId);
            Assertions.assertEquals(baseStock, afterStock.getStock(),
                    "被拒的新单不应扣减库存（事务已回滚）");

        } finally {
            initialStock.setStock(baseStock);
            ticketStockMapper.updateById(initialStock);
            ticketOrderMapper.deleteById(existingOrderId);
            ticketOrderMapper.deleteById(newOrderId);
        }
    }

    @Test
    @DisplayName("8. 测试成交价冻结：预约后票档调价，落库仍按预约时的快照价")
    void testCreateOrder_WhenTicketRepricedAfterReserve_ShouldUseSnapshotPrice() {
        Long testTicketId = 1L;
        Long testUserId = 88809L;
        Long testOrderId = 77709L;

        TicketStock initialStock = ticketStockMapper.selectById(testTicketId);
        int baseStock = initialStock.getStock();
        Ticket ticket = ticketMapper.selectById(testTicketId);
        Long basePrice = ticket.getPrice();

        ticketOrderMapper.deleteById(testOrderId);

        try {
            // 预约那一刻：消息带上当时的票价
            TicketOrderMessage message = new TicketOrderMessage();
            message.setId(testOrderId);
            message.setUserId(testUserId);
            message.setTicketId(testTicketId);
            message.setUseCredits(false);
            message.setPrice(basePrice);

            // 预约与落库之间运营涨价 500 元（MQ 投递延迟、消费重试、死信重入都能拉长这个窗口）
            Ticket repriced = new Ticket();
            repriced.setId(testTicketId);
            repriced.setPrice(basePrice + 50000L);
            ticketMapper.updateById(repriced);

            ticketOrderService.createTicketOrder(message);

            TicketOrder saved = ticketOrderMapper.selectById(testOrderId);
            Assertions.assertNotNull(saved, "订单应正常落库");
            Assertions.assertEquals(basePrice, saved.getPrice(),
                    "成交价必须是预约时的快照价，重读 tb_ticket.price 会让用户按新价成交");

        } finally {
            Ticket restore = new Ticket();
            restore.setId(testTicketId);
            restore.setPrice(basePrice);
            ticketMapper.updateById(restore);
            initialStock.setStock(baseStock);
            ticketStockMapper.updateById(initialStock);
            ticketOrderMapper.deleteById(testOrderId);
        }
    }

    @Test
    @DisplayName("9. 测试启动预热重建资格 Set：Redis 丢数据后，已持活跃订单的用户不能再抢")
    void testCacheInitializer_ShouldRebuildReservationSetFromActiveOrders() {
        Long testTicketId = 99910L;
        Long testUserId = 88810L;
        Long testOrderId = 77710L;

        String stockKey = "tc:ticket:{" + testTicketId + "}:stock";
        String orderKey = "tc:ticket:{" + testTicketId + "}:order";

        ticketOrderMapper.deleteById(testOrderId);
        // MySQL 里有一张活跃订单（已出票）
        TicketOrder order = new TicketOrder();
        order.setId(testOrderId);
        order.setUserId(testUserId);
        order.setTicketId(testTicketId);
        order.setEventId(1L);
        order.setPrice(100L);
        order.setStatus(1);
        ticketOrderMapper.insert(order);

        // 模拟 Redis flush 之后的状态：库存键已由预热补回，但资格 Set 是空的
        stringRedisTemplate.opsForValue().set(stockKey, "10");
        stringRedisTemplate.delete(orderKey);

        try {
            cacheInitializer.run(null);

            Assertions.assertTrue(
                    Boolean.TRUE.equals(stringRedisTemplate.opsForSet().isMember(orderKey, testUserId.toString())),
                    "预热必须把活跃订单的用户写回资格 Set");

            // 真正要守住的是行为：Lua 再次预约应判为已限购(2)，而不是放行(0)
            Long result = ticketReservationScript.reserve(testTicketId, testUserId);
            Assertions.assertEquals(2L, result,
                    "资格 Set 已重建，已持活跃订单的用户重复预约应返回 2（已限购）");
            Assertions.assertEquals("10", stringRedisTemplate.opsForValue().get(stockKey),
                    "被限购拦下时不应扣减库存");
        } finally {
            ticketOrderMapper.deleteById(testOrderId);
            stringRedisTemplate.delete(stockKey);
            stringRedisTemplate.delete(orderKey);
        }
    }

    @Test
    @DisplayName("10. 测试预热只认活跃订单：已取消的订单不应恢复购票资格")
    void testCacheInitializer_ShouldNotRestoreEligibilityForCancelledOrders() {
        Long testTicketId = 99911L;
        Long testUserId = 88811L;
        Long testOrderId = 77711L;

        String stockKey = "tc:ticket:{" + testTicketId + "}:stock";
        String orderKey = "tc:ticket:{" + testTicketId + "}:order";

        ticketOrderMapper.deleteById(testOrderId);
        // 已取消的订单不占购票资格，取消后用户本就可以重新购买
        TicketOrder cancelled = new TicketOrder();
        cancelled.setId(testOrderId);
        cancelled.setUserId(testUserId);
        cancelled.setTicketId(testTicketId);
        cancelled.setEventId(1L);
        cancelled.setPrice(100L);
        cancelled.setStatus(2);
        ticketOrderMapper.insert(cancelled);

        stringRedisTemplate.opsForValue().set(stockKey, "10");
        stringRedisTemplate.delete(orderKey);

        try {
            cacheInitializer.run(null);

            Assertions.assertFalse(
                    Boolean.TRUE.equals(stringRedisTemplate.opsForSet().isMember(orderKey, testUserId.toString())),
                    "已取消订单的用户不应被写回资格 Set，否则取消后再也买不了");

            Long result = ticketReservationScript.reserve(testTicketId, testUserId);
            Assertions.assertEquals(0L, result, "取消过订单的用户应能正常重新预约");
        } finally {
            ticketOrderMapper.deleteById(testOrderId);
            stringRedisTemplate.delete(stockKey);
            stringRedisTemplate.delete(orderKey);
        }
    }
}
