package asia.creat.mq;

import asia.creat.common.exception.BusinessException;
import asia.creat.config.TicketStockCacheInitializer;
import asia.creat.dto.TicketOrderMessage;
import asia.creat.dto.UserDTO;
import asia.creat.entity.ReservationTask;
import asia.creat.entity.Ticket;
import asia.creat.entity.TicketReservation;
import asia.creat.entity.TicketStock;
import asia.creat.mapper.ReservationTaskMapper;
import asia.creat.mapper.TicketMapper;
import asia.creat.mapper.TicketOrderMapper;
import asia.creat.mapper.TicketReservationMapper;
import asia.creat.mapper.TicketStockMapper;
import asia.creat.service.TicketOrderService;
import asia.creat.service.TicketReservationService;
import asia.creat.support.IntegrationTestcontainers;
import asia.creat.utils.RedisConstants;
import asia.creat.utils.RedisIdWorker;
import asia.creat.utils.TicketReservationScript;
import asia.creat.utils.UserHolder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
class TicketReservationFlowTest extends IntegrationTestcontainers {

    private static final long TICKET_ID = 99881L;
    private static final long USER_ID = 99882L;

    @Autowired
    private TicketReservationService reservationService;
    @Autowired
    private TicketOrderService orderService;
    @Autowired
    private TicketReservationTaskProcessor processor;
    @Autowired
    private TicketStockCacheInitializer initializer;
    @MockitoSpyBean
    private TicketReservationMapper reservationMapper;
    @MockitoSpyBean
    private TicketMapper ticketMapper;
    @MockitoSpyBean
    private TicketStockMapper stockMapper;
    @Autowired
    private TicketOrderMapper orderMapper;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private RabbitListenerEndpointRegistry listeners;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private TicketReservationScript script;
    @MockitoSpyBean
    private ReservationTaskMapper taskMapper;
    @MockitoSpyBean
    private TicketOrderProducer producer;
    @MockitoSpyBean
    private RedisIdWorker redisIdWorker;

    @BeforeEach
    void setUp() {
        doNothing().when(producer).send(any());
        ticketMapper.insert(new Ticket().setId(TICKET_ID).setEventId(1L)
                .setTitle("预约测试票档").setPrice(1000L).setStatus(1).setType(0));
        TicketStock stock = new TicketStock();
        stock.setTicketId(TICKET_ID);
        stock.setStock(2);
        stock.setBeginTime(LocalDateTime.now().minusHours(1));
        stock.setEndTime(LocalDateTime.now().plusHours(1));
        stockMapper.insert(stock);
        redis.opsForValue().set(RedisConstants.ticketStockKey(TICKET_ID), "2");
        UserDTO user = new UserDTO();
        user.setId(USER_ID);
        UserHolder.saveUser(user);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
        List<TicketReservation> reservations = reservationMapper.selectList(
                new LambdaQueryWrapper<TicketReservation>().eq(TicketReservation::getTicketId, TICKET_ID));
        for (TicketReservation reservation : reservations) {
            taskMapper.delete(new LambdaQueryWrapper<ReservationTask>()
                    .eq(ReservationTask::getReservationId, reservation.getId()));
            orderMapper.deleteById(reservation.getOrderId());
            reservationMapper.deleteById(reservation.getId());
        }
        stockMapper.deleteById(TICKET_ID);
        ticketMapper.deleteById(TICKET_ID);
        redis.delete(keys());
    }

    @Test
    @DisplayName("相同请求返回同一预约号，修改请求内容会被拒绝")
    void repeatedRequestReturnsSameReservation() {
        Long id = reservationService.reserveTicket(TICKET_ID, false, "same-request");
        assertEquals(id, reservationService.reserveTicket(TICKET_ID, false, "same-request"));
        assertThrows(BusinessException.class,
                () -> reservationService.reserveTicket(TICKET_ID, true, "same-request"));
        assertEquals(1, taskMapper.selectCount(new LambdaQueryWrapper<ReservationTask>()
                .eq(ReservationTask::getReservationId, id)));
        assertEquals(TicketReservation.PROCESSING, reservationService.getReservation(id).getStatus());
        assertNull(reservationService.getReservation(id).getOrderId());
        UserDTO anotherUser = new UserDTO();
        anotherUser.setId(USER_ID + 1);
        UserHolder.saveUser(anotherUser);
        assertThrows(BusinessException.class, () -> reservationService.getReservation(id));
    }

    @ParameterizedTest
    @CsvSource({"0,false", "1,false", "2,true", "3,true"})
    @DisplayName("有效预约和待回补预约提前拒绝，不再查票档、生成 ID 或锁库存")
    void activeReservationIsRejectedBeforeStockLock(int status, boolean releasePending) {
        Long id = reservationService.reserveTicket(TICKET_ID, false, "active-request");
        reservationMapper.update(null, new LambdaUpdateWrapper<TicketReservation>()
                .eq(TicketReservation::getId, id)
                .set(TicketReservation::getStatus, status)
                .set(TicketReservation::getReleasePending, releasePending));
        clearInvocations(ticketMapper, stockMapper, redisIdWorker, taskMapper);

        BusinessException error = assertThrows(BusinessException.class,
                () -> reservationService.reserveTicket(TICKET_ID, false, "another-request"));

        assertEquals("已有有效预约，请查看预约记录", error.getMessage());
        verifyNoInteractions(ticketMapper, stockMapper, redisIdWorker, taskMapper);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("相同请求在两次查询之间提交，仍按请求号校验并返回")
    void requestCommittedBeforeActiveCheckRemainsIdempotent(boolean useCredits) {
        Long id = reservationService.reserveTicket(TICKET_ID, false, "racing-request");
        doReturn(null).when(reservationMapper).selectOne(argThat(query -> query instanceof LambdaQueryWrapper));
        clearInvocations(ticketMapper, stockMapper, redisIdWorker, taskMapper);

        if (useCredits) {
            BusinessException error = assertThrows(BusinessException.class,
                    () -> reservationService.reserveTicket(TICKET_ID, true, "racing-request"));
            assertEquals(409, error.getCode());
        } else {
            assertEquals(id, reservationService.reserveTicket(TICKET_ID, false, "racing-request"));
        }
        verifyNoInteractions(ticketMapper, stockMapper, redisIdWorker, taskMapper);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("两个请求都未查到预约时，由唯一约束保证只创建一笔")
    void concurrentRequestsStillCreateOneReservation(boolean sameRequest) throws Exception {
        TicketStock stock = stockMapper.selectById(TICKET_ID);
        CountDownLatch stockReads = new CountDownLatch(2);
        doAnswer(invocation -> {
            stockReads.countDown();
            assertTrue(stockReads.await(5, TimeUnit.SECONDS));
            return stock;
        }).when(stockMapper).selectById(TICKET_ID);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Long>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String requestId = sameRequest ? "concurrent-request" : "concurrent-request-" + i;
                results.add(pool.submit(() -> {
                    UserDTO user = new UserDTO();
                    user.setId(USER_ID);
                    UserHolder.saveUser(user);
                    try {
                        return reservationService.reserveTicket(TICKET_ID, false, requestId);
                    } finally {
                        UserHolder.removeUser();
                    }
                }));
            }
            TicketReservation created = null;
            int rejected = 0;
            for (Future<Long> result : results) {
                try {
                    Long id = result.get(10, TimeUnit.SECONDS);
                    if (created == null) {
                        created = reservationMapper.selectById(id);
                    }
                    assertNotNull(created);
                    assertEquals(created.getId(), id);
                } catch (ExecutionException e) {
                    BusinessException error = assertInstanceOf(BusinessException.class, e.getCause());
                    assertEquals("已有有效预约，请查看预约记录", error.getMessage());
                    rejected++;
                }
            }
            assertNotNull(created);
            assertEquals(sameRequest ? 0 : 1, rejected);
            assertEquals(1, reservationMapper.selectCount(new LambdaQueryWrapper<TicketReservation>()
                    .eq(TicketReservation::getTicketId, TICKET_ID)));
            assertEquals(1, taskMapper.selectCount(new LambdaQueryWrapper<ReservationTask>()
                    .eq(ReservationTask::getReservationId, created.getId())));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    @DisplayName("重启不会给尚未预扣的预约补上占票记录")
    void restartBeforePreDeductionStillDeductsStock() {
        reservationService.reserveTicket(TICKET_ID, false, "before-reserve");
        initializer.run(null);
        assertNull(redis.opsForHash().get(RedisConstants.ticketReservationKey(TICKET_ID), String.valueOf(USER_ID)));
        processor.processTasks();
        assertEquals("1", redis.opsForValue().get(RedisConstants.ticketStockKey(TICKET_ID)));
    }

    @Test
    @DisplayName("预扣后发送失败，重启重试不会重复扣库存")
    void publishFailureCanRecoverAfterRestart() {
        Long id = reservationService.reserveTicket(TICKET_ID, false, "publish-retry");
        doThrow(new IllegalStateException("broker unavailable")).doNothing().when(producer).send(any());
        processor.processTasks();
        assertEquals("1", redis.opsForValue().get(RedisConstants.ticketStockKey(TICKET_ID)));
        initializer.run(null);
        retryNow(id);
        processor.processTasks();
        ArgumentCaptor<TicketOrderMessage> messages = ArgumentCaptor.forClass(TicketOrderMessage.class);
        verify(producer, times(2)).send(messages.capture());
        assertEquals(messages.getAllValues().get(0), messages.getAllValues().get(1));
        assertTrue(orderService.createTicketOrder(messages.getValue()));
        assertFalse(orderService.createTicketOrder(messages.getValue()));
        assertEquals(TicketReservation.SUCCESS, reservationService.getReservation(id).getStatus());
        assertStock(1);
    }

    @Test
    @DisplayName("Redis 丢失后，已入队消息仍会重新校验并预扣库存")
    void queuedMessageAfterRedisLossKeepsStockConsistent() {
        Long id = reservationService.reserveTicket(TICKET_ID, false, "redis-loss");
        TicketOrderMessage message = publish();
        redis.delete(keys());
        initializer.run(null);
        assertTrue(orderService.createTicketOrder(message));
        assertEquals(TicketReservation.SUCCESS, reservationService.getReservation(id).getStatus());
        assertStock(1);
    }

    @Test
    @DisplayName("取消后预约和恢复任务都提交完成，可以再次预约")
    void cancellationCompletesReleaseInNewTransaction() {
        Long id = reservationService.reserveTicket(TICKET_ID, false, "cancel");
        TicketOrderMessage message = publish();
        orderService.createTicketOrder(message);
        orderService.cancel(message.getId());
        assertEquals(TicketReservation.CANCELLED, reservationService.getReservation(id).getStatus());
        assertFalse(reservationMapper.selectById(id).getReleasePending());
        assertEquals(1, task(id, ReservationTask.RELEASE).getStatus());
        assertStock(2);
        assertNotEquals(id, reservationService.reserveTicket(TICKET_ID, false, "after-cancel"));
    }

    @Test
    @DisplayName("取消后 Redis 回补失败，恢复任务只回补一次")
    void failedReleaseIsRetried() {
        Long id = reservationService.reserveTicket(TICKET_ID, false, "release-retry");
        TicketOrderMessage message = publish();
        orderService.createTicketOrder(message);
        doThrow(new IllegalStateException("redis unavailable")).doCallRealMethod()
                .when(script).rollback(TICKET_ID, USER_ID, id);
        orderService.cancel(message.getId());
        assertTrue(reservationMapper.selectById(id).getReleasePending());
        assertEquals(0, task(id, ReservationTask.RELEASE).getStatus());
        processor.processTasks();
        processor.processTasks();
        assertFalse(reservationMapper.selectById(id).getReleasePending());
        assertEquals(1, task(id, ReservationTask.RELEASE).getStatus());
        assertStock(2);
    }

    @Test
    @DisplayName("消息中的订单号、用户、票档必须与预约匹配")
    void mismatchedMessageCannotCreateOrder() {
        Long id = reservationService.reserveTicket(TICKET_ID, false, "invalid-message");
        TicketOrderMessage message = publish();
        message.setId(message.getId() + 1);
        assertThrows(BusinessException.class, () -> orderService.createTicketOrder(message));
        assertEquals(TicketReservation.PROCESSING, reservationService.getReservation(id).getStatus());
        assertEquals(2, stockMapper.selectById(TICKET_ID).getStock());
    }

    @Test
    @DisplayName("旧版回滚重复调用不会多加库存")
    void legacyRollbackIsIdempotent() {
        assertEquals(0L, script.reserve(TICKET_ID, USER_ID));
        assertEquals(1L, script.rollback(TICKET_ID, USER_ID));
        assertEquals(0L, script.rollback(TICKET_ID, USER_ID));
        assertStock(2);
    }

    @Test
    @DisplayName("迟到的旧版回滚不能释放新预约")
    void legacyRollbackCannotReleaseNewReservation() {
        assertEquals(0L, script.reserve(TICKET_ID, USER_ID, 10001L));
        assertEquals(0L, script.rollback(TICKET_ID, USER_ID));
        assertEquals("1", redis.opsForValue().get(RedisConstants.ticketStockKey(TICKET_ID)));
    }

    @Test
    @DisplayName("已受理预约经过真实 RabbitMQ 消费后生成待支付订单")
    void reservationCreatesOrderThroughRabbitMq() {
        doCallRealMethod().when(producer).send(any());
        listeners.start();
        try {
            Long id = reservationService.reserveTicket(TICKET_ID, false, "rabbitmq");
            processor.processTasks();
            await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                    assertEquals(TicketReservation.SUCCESS, reservationMapper.selectById(id).getStatus()));
            assertEquals(0, reservationService.getReservation(id).getOrderStatus());
            assertStock(1);
        } finally {
            listeners.stop();
        }
    }

    @Test
    @DisplayName("订单事务中途失败后可以重试，不重复扣减库存")
    void orderTransactionFailureCanRecover() {
        Long id = reservationService.reserveTicket(TICKET_ID, false, "transaction-retry");
        TicketOrderMessage message = publish();
        doThrow(new IllegalStateException("task update failed"))
                .when(taskMapper).complete(id, ReservationTask.SEND_ORDER);
        try {
            assertThrows(IllegalStateException.class, () -> orderService.createTicketOrder(message));
        } finally {
            reset(taskMapper);
        }
        assertNull(orderMapper.selectById(message.getId()));
        assertEquals(TicketReservation.PROCESSING, reservationMapper.selectById(id).getStatus());
        assertEquals(2, stockMapper.selectById(TICKET_ID).getStock());
        assertEquals(0, task(id, ReservationTask.SEND_ORDER).getStatus());
        assertTrue(orderService.createTicketOrder(message));
        assertEquals(1, task(id, ReservationTask.SEND_ORDER).getStatus());
        assertStock(1);
    }

    @Test
    @DisplayName("售罄预约有明确失败结果，不会凭空回补库存")
    void soldOutReservationHasFinalResult() {
        stockMapper.update(null, new LambdaUpdateWrapper<TicketStock>()
                .eq(TicketStock::getTicketId, TICKET_ID).set(TicketStock::getStock, 0));
        redis.opsForValue().set(RedisConstants.ticketStockKey(TICKET_ID), "0");
        Long id = reservationService.reserveTicket(TICKET_ID, false, "sold-out");
        processor.processTasks();
        processor.processTasks();
        assertEquals(TicketReservation.FAILED, reservationService.getReservation(id).getStatus());
        assertEquals("库存不足", reservationService.getReservation(id).getFailureReason());
        assertFalse(reservationMapper.selectById(id).getReleasePending());
        verify(producer, never()).send(any());
        assertStock(0);
    }

    @Test
    @DisplayName("旧消息和旧补偿到达时不影响同一用户的新预约")
    void lateMessageCannotReopenFailedReservation() {
        Long id = reservationService.reserveTicket(TICKET_ID, false, "old-reservation");
        TicketOrderMessage oldMessage = publish();
        reservationService.failReservation(id, "订单处理失败");
        processor.processTasks();
        Long newId = reservationService.reserveTicket(TICKET_ID, false, "new-reservation");
        processor.processTasks();
        assertFalse(orderService.createTicketOrder(oldMessage));
        reservationService.releaseReservation(id);
        assertEquals("1", redis.opsForValue().get(RedisConstants.ticketStockKey(TICKET_ID)));
        assertEquals(newId.toString(), redis.opsForHash().get(
                RedisConstants.ticketReservationKey(TICKET_ID), String.valueOf(USER_ID)));
    }

    @Test
    @DisplayName("恢复任务预扣时，失败处理必须等同一预约处理完成")
    void preDeductionAndFailureAreSerialized() throws Exception {
        Long id = reservationService.reserveTicket(TICKET_ID, false, "concurrent-failure");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        CountDownLatch failureStarted = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertTrue(proceed.await(5, TimeUnit.SECONDS));
            return invocation.callRealMethod();
        }).when(script).reserve(TICKET_ID, USER_ID, id);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> sending = pool.submit(processor::processTasks);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Future<?> failing = pool.submit(() -> {
                failureStarted.countDown();
                reservationService.failReservation(id, "订单处理失败");
            });
            assertTrue(failureStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> failing.get(200, TimeUnit.MILLISECONDS));
            proceed.countDown();
            sending.get(5, TimeUnit.SECONDS);
            failing.get(5, TimeUnit.SECONDS);
            processor.processTasks();
            assertEquals(TicketReservation.FAILED, reservationService.getReservation(id).getStatus());
            assertFalse(reservationMapper.selectById(id).getReleasePending());
            assertStock(2);
        } finally {
            proceed.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    @DisplayName("支付期限内可以支付，重复支付和超时消息不会改变已出票订单")
    void paymentWithinDeadlineSucceedsOnce() {
        Long id = reservationService.reserveTicket(TICKET_ID, false, "pay");
        TicketOrderMessage message = publish();
        orderService.createTicketOrder(message);
        orderService.pay(message.getId());
        assertThrows(BusinessException.class, () -> orderService.pay(message.getId()));
        orderService.cancelTimeoutOrder(message.getId());
        assertEquals(1, orderMapper.selectById(message.getId()).getStatus());
        assertEquals(TicketReservation.SUCCESS, reservationMapper.selectById(id).getStatus());
        assertStock(1);
    }

    @Test
    @DisplayName("订单到期但关单任务尚未执行时，支付也必须被拒绝")
    void expiredOrderCannotBePaidBeforeCancellation() {
        reservationService.reserveTicket(TICKET_ID, false, "expired-pay");
        TicketOrderMessage message = publish();
        orderService.createTicketOrder(message);
        expireOrder(message.getId());
        assertThrows(BusinessException.class, () -> orderService.pay(message.getId()));
        assertEquals(0, orderMapper.selectById(message.getId()).getStatus());
        assertStock(1);
    }

    @Test
    @DisplayName("提前到达的关单消息不能取消尚未到期的订单")
    void earlyCancellationMessageDoesNotReleaseStock() {
        reservationService.reserveTicket(TICKET_ID, false, "early-cancel");
        TicketOrderMessage message = publish();
        orderService.createTicketOrder(message);
        orderService.cancelTimeoutOrder(message.getId());
        assertEquals(0, orderMapper.selectById(message.getId()).getStatus());
        assertStock(1);
    }

    @Test
    @DisplayName("到达截止时间后可以关单，重复关单只释放一次库存")
    void expiredOrderIsCancelledOnce() {
        Long id = reservationService.reserveTicket(TICKET_ID, false, "expired-cancel");
        TicketOrderMessage message = publish();
        orderService.createTicketOrder(message);
        expireOrder(message.getId());
        orderService.cancelTimeoutOrder(message.getId());
        orderService.cancelTimeoutOrder(message.getId());
        assertEquals(2, orderMapper.selectById(message.getId()).getStatus());
        assertEquals(TicketReservation.CANCELLED, reservationMapper.selectById(id).getStatus());
        assertStock(2);
    }

    @Test
    @DisplayName("支付与超时关单并发时，过期订单只能关闭")
    void expiredPaymentAndCancellationHaveOneResult() throws Exception {
        reservationService.reserveTicket(TICKET_ID, false, "pay-cancel-race");
        TicketOrderMessage message = publish();
        orderService.createTicketOrder(message);
        expireOrder(message.getId());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> paying = pool.submit(() -> {
                UserDTO user = new UserDTO();
                user.setId(USER_ID);
                UserHolder.saveUser(user);
                try {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    assertThrows(BusinessException.class, () -> orderService.pay(message.getId()));
                    return null;
                } finally {
                    UserHolder.removeUser();
                }
            });
            Future<?> cancelling = pool.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                orderService.cancelTimeoutOrder(message.getId());
                return null;
            });
            start.countDown();
            paying.get(10, TimeUnit.SECONDS);
            cancelling.get(10, TimeUnit.SECONDS);
            assertEquals(2, orderMapper.selectById(message.getId()).getStatus());
            assertStock(2);
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private void expireOrder(Long orderId) {
        jdbcTemplate.update("UPDATE tb_ticket_order SET create_time = DATE_SUB(NOW(), INTERVAL ? SECOND) WHERE id = ?",
                RedisConstants.ORDER_TIMEOUT.toSeconds(), orderId);
    }

    private TicketOrderMessage publish() {
        processor.processTasks();
        ArgumentCaptor<TicketOrderMessage> message = ArgumentCaptor.forClass(TicketOrderMessage.class);
        verify(producer).send(message.capture());
        return message.getValue();
    }

    private ReservationTask task(Long id, int type) {
        return taskMapper.selectOne(new LambdaQueryWrapper<ReservationTask>()
                .eq(ReservationTask::getReservationId, id).eq(ReservationTask::getType, type));
    }

    private void retryNow(Long id) {
        taskMapper.update(null, new LambdaUpdateWrapper<ReservationTask>()
                .eq(ReservationTask::getReservationId, id)
                .setSql("next_retry_time = DATE_SUB(NOW(3), INTERVAL 1 SECOND)"));
    }

    private void assertStock(int expected) {
        assertEquals(expected, stockMapper.selectById(TICKET_ID).getStock());
        assertEquals(String.valueOf(expected), redis.opsForValue().get(RedisConstants.ticketStockKey(TICKET_ID)));
    }

    private List<String> keys() {
        return List.of(RedisConstants.ticketStockKey(TICKET_ID), RedisConstants.ticketOrderKey(TICKET_ID),
                RedisConstants.ticketReservationKey(TICKET_ID));
    }
}
