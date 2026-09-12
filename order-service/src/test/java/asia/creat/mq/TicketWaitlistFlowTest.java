package asia.creat.mq;

import asia.creat.common.exception.BusinessException;
import asia.creat.config.TicketStockCacheInitializer;
import asia.creat.dto.PageQuery;
import asia.creat.dto.TicketOrderMessage;
import asia.creat.dto.UserDTO;
import asia.creat.entity.ReservationTask;
import asia.creat.entity.Ticket;
import asia.creat.entity.TicketReservation;
import asia.creat.entity.TicketStock;
import asia.creat.entity.TicketWaitlist;
import asia.creat.mapper.ReservationTaskMapper;
import asia.creat.mapper.TicketMapper;
import asia.creat.mapper.TicketOrderMapper;
import asia.creat.mapper.TicketReservationMapper;
import asia.creat.mapper.TicketStockMapper;
import asia.creat.mapper.TicketWaitlistMapper;
import asia.creat.service.TicketOrderService;
import asia.creat.service.TicketReservationService;
import asia.creat.service.TicketWaitlistService;
import asia.creat.support.IntegrationTestcontainers;
import asia.creat.utils.RedisConstants;
import asia.creat.utils.TicketReservationScript;
import asia.creat.utils.UserHolder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
class TicketWaitlistFlowTest extends IntegrationTestcontainers {

    private static final long TICKET_ID = 99781L;
    private static final long OWNER = 99782L;
    private static final long FIRST = 99783L;
    private static final long SECOND = 99784L;

    @Autowired
    private TicketWaitlistService waitlistService;
    @Autowired
    private TicketReservationService reservationService;
    @Autowired
    private TicketOrderService orderService;
    @Autowired
    private TicketReservationTaskProcessor processor;
    @Autowired
    private TicketStockCacheInitializer initializer;
    @Autowired
    private TicketWaitlistMapper waitlistMapper;
    @Autowired
    private TicketReservationMapper reservationMapper;
    @Autowired
    private TicketMapper ticketMapper;
    @Autowired
    private TicketStockMapper stockMapper;
    @Autowired
    private TicketOrderMapper orderMapper;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private TicketReservationScript script;
    @MockitoSpyBean
    private ReservationTaskMapper taskMapper;
    @MockitoSpyBean
    private TicketOrderProducer producer;

    private Long ownerOrderId;

    @BeforeEach
    void setUp() {
        doNothing().when(producer).send(any());
        ticketMapper.insert(new Ticket().setId(TICKET_ID).setEventId(1L)
                .setTitle("候补测试票档").setPrice(1000L).setStatus(1).setType(0));
        TicketStock stock = new TicketStock();
        stock.setTicketId(TICKET_ID);
        stock.setStock(1);
        stock.setBeginTime(LocalDateTime.now().minusHours(1));
        stock.setEndTime(LocalDateTime.now().plusHours(1));
        stockMapper.insert(stock);
        redis.opsForValue().set(RedisConstants.ticketStockKey(TICKET_ID), "1");
        login(OWNER);
        Long reservationId = reservationService.reserveTicket(TICKET_ID, false, "owner");
        ownerOrderId = consume(reservationId);
        clearInvocations(producer);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
        waitlistMapper.delete(new LambdaQueryWrapper<TicketWaitlist>().eq(TicketWaitlist::getTicketId, TICKET_ID));
        for (TicketReservation reservation : reservationMapper.selectList(
                new LambdaQueryWrapper<TicketReservation>().eq(TicketReservation::getTicketId, TICKET_ID))) {
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
    @DisplayName("售罄入队不扣库存，同一请求幂等且不能修改积分选项")
    void joinIsIdempotentAndDoesNotReserveStock() {
        Long id = join(FIRST);
        assertEquals(id, waitlistService.join(TICKET_ID, false, "join-" + FIRST));
        assertThrows(BusinessException.class, () -> waitlistService.join(TICKET_ID, true, "join-" + FIRST));
        assertThrows(BusinessException.class, () -> waitlistService.join(TICKET_ID, false, "another-request"));
        assertEquals(TicketWaitlist.WAITING, waitlistService.getWaitlist(id).getStatus());
        assertEquals(1L, waitlistService.getWaitlist(id).getPosition());
        assertNull(waitlistService.getWaitlist(id).getReservationId());
        assertEquals(1, waitlistService.myWaitlists(new PageQuery()).getTotal());
        verify(producer, never()).send(any());
        assertStock(0);
    }

    @Test
    @DisplayName("有余票或已有有效订单时不能候补")
    void availableStockAndActiveOrdersCannotJoin() {
        assertThrows(BusinessException.class, () -> waitlistService.join(TICKET_ID, false, "owner-join"));
        releaseOwner();
        login(FIRST);
        assertThrows(BusinessException.class, () -> waitlistService.join(TICKET_ID, false, "available"));
        assertStock(1);
    }

    @Test
    @DisplayName("候补记录和取消操作只允许本人访问，取消后重排在队尾")
    void cancellationChecksOwnerAndRejoinGoesToTail() {
        Long first = join(FIRST);
        Long second = join(SECOND);
        assertThrows(BusinessException.class, () -> waitlistService.getWaitlist(first));
        assertThrows(BusinessException.class, () -> waitlistService.cancel(first));
        login(FIRST);
        waitlistService.cancel(first);
        waitlistService.cancel(first);
        Long rejoined = waitlistService.join(TICKET_ID, false, "rejoin");
        assertTrue(rejoined > second);
        assertEquals(2L, waitlistService.getWaitlist(rejoined).getPosition());
        assertStock(0);
    }

    @Test
    @DisplayName("释放的名额优先给队首，重复递补不会创建多个预约")
    void releasedStockGoesToHeadBeforeNewReservations() {
        Long first = join(FIRST);
        Long second = join(SECOND);
        releaseOwner();
        login(SECOND + 1);
        assertThrows(BusinessException.class,
                () -> reservationService.reserveTicket(TICKET_ID, false, "jump-queue"));
        waitlistService.processWaitlists();
        waitlistService.processWaitlists();
        assertEquals(TicketWaitlist.ALLOCATING, entry(first).getStatus());
        assertEquals(TicketWaitlist.WAITING, entry(second).getStatus());
        assertEquals(2, reservationMapper.selectCount(new LambdaQueryWrapper<TicketReservation>()
                .eq(TicketReservation::getTicketId, TICKET_ID)));
        Long orderId = consume(entry(first).getReservationId());
        waitlistService.processWaitlists();
        login(FIRST);
        assertEquals(orderId, waitlistService.getWaitlist(first).getOrderId());
        assertNotNull(waitlistService.getWaitlist(first).getPaymentDeadline());
        orderService.pay(orderId);
        waitlistService.processWaitlists();
        assertEquals(TicketWaitlist.ALLOCATED, entry(first).getStatus());
        assertEquals(TicketWaitlist.WAITING, entry(second).getStatus());
        assertStock(0);
    }

    @Test
    @DisplayName("递补订单超时后继续给下一位，不自动把原用户放回队列")
    void expiredAllocationPassesToNextUser() {
        Long first = join(FIRST);
        Long second = join(SECOND);
        releaseOwner();
        waitlistService.processWaitlists();
        Long orderId = consume(entry(first).getReservationId());
        jdbcTemplate.update("UPDATE tb_ticket_order SET create_time = DATE_SUB(NOW(), INTERVAL 16 MINUTE) WHERE id = ?",
                orderId);
        orderService.cancelTimeoutOrder(orderId);
        orderService.cancelTimeoutOrder(orderId);
        waitlistService.processWaitlists();
        assertEquals(TicketWaitlist.ALLOCATED, entry(first).getStatus());
        assertEquals(TicketWaitlist.ALLOCATING, entry(second).getStatus());
        consume(entry(second).getReservationId());
        assertStock(0);
    }

    @Test
    @DisplayName("消息发送失败时保留队首，恢复后仍由同一预约建单")
    void publishFailureKeepsQueueOrder() {
        Long first = join(FIRST);
        Long second = join(SECOND);
        releaseOwner();
        waitlistService.processWaitlists();
        Long reservationId = entry(first).getReservationId();
        doThrow(new IllegalStateException("broker unavailable")).when(producer).send(any());
        processor.processTasks();
        waitlistService.processWaitlists();
        assertEquals(reservationId, entry(first).getReservationId());
        assertEquals(TicketWaitlist.WAITING, entry(second).getStatus());
        doNothing().when(producer).send(any());
        jdbcTemplate.update("UPDATE tb_reservation_task SET next_retry_time = NOW(3) WHERE reservation_id = ?",
                reservationId);
        consume(reservationId);
        assertStock(0);
    }

    @Test
    @DisplayName("递补预约失败并完成回补后按原顺序重试，旧消息不能影响新预约")
    void failedReservationRetriesAtOriginalPosition() {
        Long first = join(FIRST);
        Long second = join(SECOND);
        releaseOwner();
        waitlistService.processWaitlists();
        Long oldId = entry(first).getReservationId();
        processor.processTasks();
        TicketOrderMessage oldMessage = message(oldId);
        reservationService.failReservation(oldId, "订单处理失败");
        waitlistService.processWaitlists();
        assertEquals(oldId, entry(first).getReservationId());
        processor.processTasks();
        waitlistService.processWaitlists();
        Long newId = entry(first).getReservationId();
        assertNotEquals(oldId, newId);
        assertEquals(TicketWaitlist.WAITING, entry(second).getStatus());
        consume(newId);
        assertFalse(orderService.createTicketOrder(oldMessage));
        reservationService.releaseReservation(oldId);
        assertStock(0);
    }

    @Test
    @DisplayName("销售结束的排队记录失效，已递补订单不被当作排队取消")
    void saleEndExpiresOnlyWaitingEntries() {
        Long first = join(FIRST);
        Long second = join(SECOND);
        releaseOwner();
        waitlistService.processWaitlists();
        TicketStock stock = stockMapper.selectById(TICKET_ID);
        stock.setEndTime(LocalDateTime.now().minusSeconds(1));
        stockMapper.updateById(stock);
        waitlistService.processWaitlists();
        assertEquals(TicketWaitlist.ALLOCATING, entry(first).getStatus());
        assertEquals(TicketWaitlist.EXPIRED, entry(second).getStatus());
        consume(entry(first).getReservationId());
        waitlistService.processWaitlists();
        assertEquals(TicketWaitlist.ALLOCATED, entry(first).getStatus());
        assertEquals(TicketWaitlist.EXPIRED, entry(second).getStatus());
        assertStock(0);
    }

    @Test
    @DisplayName("Redis 丢失后候补仍按 MySQL 队列恢复，成交价使用入队快照")
    void redisLossPreservesQueueAndPrice() {
        Long first = join(FIRST);
        releaseOwner();
        ticketMapper.updateById(new Ticket().setId(TICKET_ID).setPrice(2000L));
        waitlistService.processWaitlists();
        processor.processTasks();
        redis.delete(keys());
        initializer.run(null);
        Long orderId = consume(entry(first).getReservationId());
        assertEquals(1000L, orderMapper.selectById(orderId).getPrice());
        assertStock(0);
    }

    @Test
    @DisplayName("多个递补任务同时执行时，同一名额只生成一个预约")
    void concurrentDispatchCreatesOneReservation() throws Exception {
        Long first = join(FIRST);
        Long second = join(SECOND);
        releaseOwner();
        race(waitlistService::processWaitlists, waitlistService::processWaitlists,
                waitlistService::processWaitlists, waitlistService::processWaitlists);
        assertEquals(TicketWaitlist.ALLOCATING, entry(first).getStatus());
        assertEquals(TicketWaitlist.WAITING, entry(second).getStatus());
        assertEquals(2, reservationMapper.selectCount(new LambdaQueryWrapper<TicketReservation>()
                .eq(TicketReservation::getTicketId, TICKET_ID)));
        consume(entry(first).getReservationId());
        assertStock(0);
    }

    @Test
    @DisplayName("售罄时普通请求在受理阶段被拒，不会抢在候补前占位")
    void acceptedRequestCannotJumpQueue() {
        login(SECOND);
        assertThrows(BusinessException.class,
                () -> reservationService.reserveTicket(TICKET_ID, false, "accepted-before-queue"));
        assertEquals(1, reservationMapper.selectCount(new LambdaQueryWrapper<TicketReservation>()
                .eq(TicketReservation::getTicketId, TICKET_ID)));
        Long first = join(FIRST);
        releaseOwner();
        processor.processTasks();
        assertStock(1);
        waitlistService.processWaitlists();
        consume(entry(first).getReservationId());
        assertStock(0);
    }

    @Test
    @DisplayName("同一用户并发提交相同候补请求只入队一次")
    void concurrentJoinIsIdempotent() throws Exception {
        Runnable joining = () -> {
            try {
                join(FIRST);
            } finally {
                UserHolder.removeUser();
            }
        };
        race(joining, joining, joining, joining);
        assertEquals(1, waitlistMapper.selectCount(new LambdaQueryWrapper<TicketWaitlist>()
                .eq(TicketWaitlist::getTicketId, TICKET_ID)));
        assertTrue(ticketMapper.queryTicketOfEvent(1L).stream()
                .filter(ticket -> ticket.getId().equals(TICKET_ID)).findFirst().orElseThrow().getHasWaitlist());
        assertStock(0);
    }

    @Test
    @DisplayName("取消候补和递补竞争时，取消或获得资格只有一个结果")
    void cancellationAndAllocationHaveOneResult() throws Exception {
        Long first = join(FIRST);
        Long second = join(SECOND);
        releaseOwner();
        race(waitlistService::processWaitlists, () -> {
            login(FIRST);
            try {
                waitlistService.cancel(first);
            } catch (BusinessException e) {
                assertEquals(TicketWaitlist.ALLOCATING, entry(first).getStatus());
            } finally {
                UserHolder.removeUser();
            }
        });
        waitlistService.processWaitlists();
        TicketWaitlist winner = entry(first).getStatus() == TicketWaitlist.CANCELLED ? entry(second) : entry(first);
        assertEquals(TicketWaitlist.ALLOCATING, winner.getStatus());
        consume(winner.getReservationId());
        assertStock(0);
    }

    private void race(Runnable... actions) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(actions.length);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (Runnable action : actions) {
                futures.add(pool.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    action.run();
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(15, TimeUnit.SECONDS);
            }
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private Long join(long userId) {
        login(userId);
        return waitlistService.join(TICKET_ID, false, "join-" + userId);
    }

    private void releaseOwner() {
        login(OWNER);
        orderService.cancel(ownerOrderId);
    }

    private TicketWaitlist entry(Long id) {
        return waitlistMapper.selectById(id);
    }

    private Long consume(Long reservationId) {
        processor.processTasks();
        TicketOrderMessage message = message(reservationId);
        assertTrue(orderService.createTicketOrder(message));
        return message.getId();
    }

    private TicketOrderMessage message(Long reservationId) {
        TicketReservation reservation = reservationMapper.selectById(reservationId);
        TicketOrderMessage message = new TicketOrderMessage();
        message.setId(reservation.getOrderId());
        message.setReservationId(reservationId);
        message.setUserId(reservation.getUserId());
        message.setTicketId(reservation.getTicketId());
        message.setUseCredits(reservation.getUseCredits());
        message.setPrice(reservation.getPrice());
        return message;
    }

    private void login(long userId) {
        UserDTO user = new UserDTO();
        user.setId(userId);
        UserHolder.saveUser(user);
    }

    private void assertStock(int expected) {
        assertEquals(expected, stockMapper.selectById(TICKET_ID).getStock());
        assertEquals(String.valueOf(expected), redis.opsForValue().get(RedisConstants.ticketStockKey(TICKET_ID)));
    }

    private List<String> keys() {
        return List.of(RedisConstants.ticketStockKey(TICKET_ID), RedisConstants.ticketOrderKey(TICKET_ID),
                RedisConstants.ticketReservationKey(TICKET_ID), RedisConstants.ticketInfoKey(TICKET_ID));
    }
}
