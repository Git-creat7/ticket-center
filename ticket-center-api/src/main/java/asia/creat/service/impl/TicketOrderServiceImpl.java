package asia.creat.service.impl;

import asia.creat.common.PageResult;
import asia.creat.common.exception.BusinessException;
import asia.creat.dto.PageQuery;
import asia.creat.dto.TicketOrderMessage;
import asia.creat.entity.Event;
import asia.creat.entity.Ticket;
import asia.creat.entity.TicketOrder;
import asia.creat.entity.TicketStock;
import asia.creat.entity.UserInfo;
import asia.creat.mapper.EventMapper;
import asia.creat.mapper.TicketMapper;
import asia.creat.mapper.TicketOrderMapper;
import asia.creat.mapper.TicketStockMapper;
import asia.creat.mq.TicketOrderProducer;
import asia.creat.service.CreditLogService;
import asia.creat.service.TicketOrderService;
import asia.creat.service.UserInfoService;
import asia.creat.utils.RedisConstants;
import asia.creat.utils.RedisIdWorker;
import asia.creat.utils.TicketReservationScript;
import asia.creat.utils.UserHolder;
import asia.creat.vo.TicketOrderVO;
import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.amqp.AmqpException;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TicketOrderServiceImpl extends ServiceImpl<TicketOrderMapper, TicketOrder> implements TicketOrderService {

    private final TicketStockMapper ticketStockMapper;
    private final TicketMapper ticketMapper;
    private final EventMapper eventMapper;
    private final RedisIdWorker redisIdWorker;
    private final TicketOrderProducer ticketOrderProducer;
    private final TransactionTemplate transactionTemplate;
    private final UserInfoService userInfoService;
    private final CreditLogService creditLogService;
    private final RedissonClient redissonClient;
    private final TicketReservationScript ticketReservationScript;
    private final StringRedisTemplate stringRedisTemplate;

    // 兜底扫描单轮处理上限。
    private static final int TIMEOUT_SCAN_BATCH_SIZE = 500;

    @Override
    public Long reserveTicket(Long ticketId, Boolean useCredits) {
        Long userId = UserHolder.getUser().getId();

        Ticket ticket = ticketMapper.selectById(ticketId);
        if (ticket == null) {
            throw new BusinessException(404, "票档不存在");
        }
        if (ticket.getStatus() == null || ticket.getStatus() != 1) {
            throw new BusinessException("票档已下架");
        }

        TicketStock ticketStock = ticketStockMapper.selectById(ticketId);
        if (ticketStock == null) {
            throw new BusinessException("票档库存信息不存在");
        }

        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(ticketStock.getBeginTime())) {
            throw new BusinessException("预约尚未开始");
        }
        if (now.isAfter(ticketStock.getEndTime())) {
            throw new BusinessException("预约已经结束");
        }

        long orderId = redisIdWorker.nextId("order");

        // Redis 原子预扣库存和一人一票资格。
        Long result = ticketReservationScript.reserve(ticketId, userId);
        if (result == null) {
            throw new BusinessException("系统繁忙，请稍后重试");
        }
        int code = result.intValue();
        if (code == 1) {
            throw new BusinessException("库存不足");
        }
        if (code == 2) {
            throw new BusinessException("每个用户限购一张");
        }
        if (code != 0) {
            throw new BusinessException("系统繁忙，请稍后重试");
        }
        TicketOrderMessage message = new TicketOrderMessage();
        message.setId(orderId);
        message.setUserId(userId);
        message.setTicketId(ticketId);
        message.setUseCredits(useCredits);
        // 保存预约时的成交价快照。
        message.setPrice(ticket.getPrice());
        try {
            ticketOrderProducer.send(message);
        } catch (AmqpException e) {
            Long rollbackResult = ticketReservationScript.rollback(ticketId, userId);

            if (rollbackResult == null || rollbackResult != 1L) {
                log.error("Redis 预约补偿失败，orderId={}", orderId);
            }
            throw new BusinessException("订单消息发送失败，请稍候重试", e);
        }
        return orderId;
    }

    @Override
    @Transactional
    public void createTicketOrder(TicketOrderMessage message) {
        Long userId = message.getUserId();

        if (getById(message.getId()) != null) {
            log.info("【重复投递】订单已存在，跳过落库, orderId={}", message.getId());
            return;
        }

        Long activeCount = query()
                .eq("user_id", userId)
                .eq("ticket_id", message.getTicketId())
                .in("status", 0, 1)
                .count();
        if (activeCount > 0) {
            throw new BusinessException("该用户已持有此票档的活跃订单");
        }

        int update = ticketStockMapper.update(
                null,
                new LambdaUpdateWrapper<TicketStock>()
                        .setSql("stock = stock - 1")
                        .eq(TicketStock::getTicketId, message.getTicketId())
                        .gt(TicketStock::getStock, 0)
        );
        if (update != 1) {
            throw new BusinessException("数据库库存不足，稍后重试");
        }

        Ticket ticket = ticketMapper.selectById(message.getTicketId());
        if (ticket == null) {
            throw new BusinessException("票档不存在");
        }

        long originalPrice = message.getPrice() != null ? message.getPrice() : ticket.getPrice();
        int deductedCredits = deductCredits(message, originalPrice);
        long finalPrice = originalPrice - deductedCredits;
        TicketOrder ticketOrder = buildOrder(message, ticket.getEventId(), finalPrice, deductedCredits);

        if (!save(ticketOrder)) {
            throw new BusinessException("保存订单失败");
        }

        evictEventDetailCacheAfterCommit(ticket.getEventId());
    }

    private int deductCredits(TicketOrderMessage message, long originalPrice) {
        if (!Boolean.TRUE.equals(message.getUseCredits())) {
            return 0;
        }

        Long userId = message.getUserId();
        UserInfo userInfo = userInfoService.getById(userId);
        int availableCredits = userInfo != null && userInfo.getCredits() != null ? userInfo.getCredits() : 0;
        int usedCredits = (int) Math.min((long) availableCredits, Math.min(1000L, originalPrice));
        if (usedCredits <= 0) {
            return 0;
        }

        boolean deducted = userInfoService.update(new LambdaUpdateWrapper<UserInfo>()
                .setSql("credits = credits - {0}", usedCredits)
                .eq(UserInfo::getUserId, userId)
                .ge(UserInfo::getCredits, usedCredits));
        if (!deducted) {
            return 0;
        }

        int afterBalance = Math.max(0, availableCredits - usedCredits);
        creditLogService.recordLog(
                userId,
                2,
                String.valueOf(message.getId()),
                -usedCredits,
                afterBalance,
                "购票抵扣立减 (订单: " + message.getId() + ")"
        );
        log.info("【购票积分抵扣成功】orderId={}, usedCredits={}, 抵扣金额={}分, 实付金额={}分",
                message.getId(), usedCredits, usedCredits, originalPrice - usedCredits);
        return usedCredits;
    }

    private TicketOrder buildOrder(
            TicketOrderMessage message, Long eventId, long finalPrice, int deductedCredits) {
        TicketOrder order = new TicketOrder();
        order.setId(message.getId());
        order.setUserId(message.getUserId());
        order.setTicketId(message.getTicketId());
        order.setEventId(eventId);
        order.setPrice(finalPrice);
        order.setUsedCredits(deductedCredits);
        order.setStatus(0);
        return order;
    }

    @Override
    public void pay(Long orderId) {
        Long userId = UserHolder.getUser().getId();
        // 只有待支付订单可以支付。
        boolean updated = update()
                .setSql("status = 1, pay_time = NOW()")
                .eq("id", orderId)
                .eq("user_id", userId)
                .eq("status", 0)
                .update();
        if (!updated) {
            throw new BusinessException("订单不存在或状态不允许支付");
        }
    }

    @Override
    @Transactional
    public void cancel(Long orderId) {
        Long userId = UserHolder.getUser().getId();
        TicketOrder order = query()
                .eq("id", orderId)
                .eq("user_id", userId)
                .one();
        if (order == null) {
            throw new BusinessException("订单不存在或无权操作");
        }
        boolean cancelled = doCancel(order);
        if (!cancelled) {
            throw new BusinessException("订单状态不允许取消");
        }
        releaseStock(order);
    }

    @Override
    public PageResult<TicketOrderVO> myOrders(PageQuery query, Integer status) {
        Long userId = UserHolder.getUser().getId();
        // 状态条件下推到 SQL，保证分页总数正确。
        Page<TicketOrder> page = query()
                .eq("user_id", userId)
                .eq(status != null, "status", status)
                .orderByDesc("create_time")
                .orderByDesc("id")
                .page(query.toPage());

        List<TicketOrder> orders = page.getRecords();
        if (orders.isEmpty()) {
            return PageResult.of(List.of(), page.getTotal(), page.getCurrent(), page.getSize());
        }

        // 批量加载关联数据，减少逐条查询。
        Set<Long> ticketIds = orders.stream().map(TicketOrder::getTicketId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> eventIds = orders.stream().map(TicketOrder::getEventId).filter(Objects::nonNull).collect(Collectors.toSet());

        Map<Long, Ticket> ticketMap = ticketIds.isEmpty() ? Map.of()
                : ticketMapper.selectBatchIds(ticketIds).stream()
                        .collect(Collectors.toMap(Ticket::getId, t -> t, (a, b) -> a));
        Map<Long, Event> eventMap = eventIds.isEmpty() ? Map.of()
                : eventMapper.selectBatchIds(eventIds).stream()
                        .collect(Collectors.toMap(Event::getId, e -> e, (a, b) -> a));

        List<TicketOrderVO> voList = orders.stream().map(order -> {
            TicketOrderVO vo = BeanUtil.copyProperties(order, TicketOrderVO.class);
            Ticket ticket = ticketMap.get(order.getTicketId());
            if (ticket != null) {
                vo.setTicketTitle(ticket.getTitle());
            }
            Event event = eventMap.get(order.getEventId());
            if (event != null) {
                vo.setEventName(event.getName());
            }
            vo.setStatusDesc(getStatusDesc(order.getStatus()));
            return vo;
        }).toList();

        return PageResult.of(voList, page.getTotal(), page.getCurrent(), page.getSize());
    }

    private String getStatusDesc(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case 0 -> "待支付";
            case 1 -> "已出票";
            case 2 -> "已取消";
            default -> "未知";
        };
    }

    @Override
    public void cancelTimeoutOrder(Long orderId) {
        TicketOrder order = getById(orderId);
        if (order == null) {
            log.warn("【延时关单】订单不存在, orderId={}", orderId);
            return;
        }
        // 只有待支付订单需要关单。
        if (order.getStatus() != null && order.getStatus() != 0) {
            log.info("【延时关单】订单状态已变更，无需处理, orderId={}, currentStatus={}", orderId, order.getStatus());
            return;
        }

        Boolean cancelled = cancelAndRelease(order);

        if (Boolean.TRUE.equals(cancelled)) {
            log.info("【延时关单成功】超时未支付订单已自动取消并释放库存, orderId={}, ticketId={}",
                    order.getId(), order.getTicketId());
        }
    }

    @Override
    @Scheduled(fixedDelay = 60000)
    public void releaseTimeoutOrders() {
        // 多实例只允许一个任务扫描超时订单。
        RLock lock = redissonClient.getLock(RedisConstants.LOCK_ORDER_KEY + "release-timeout");
        if (!lock.tryLock()) {
            return;
        }
        try {
            // 定时扫描作为延时队列的兜底。
            LocalDateTime deadline = LocalDateTime.now().minus(RedisConstants.ORDER_TIMEOUT);
            List<TicketOrder> timeoutOrders = query()
                    .eq("status", 0)
                    .lt("create_time", deadline)
                    .orderByAsc("create_time")
                    .last("limit " + TIMEOUT_SCAN_BATCH_SIZE)
                    .list();
            if (timeoutOrders.isEmpty()) {
                return;
            }
            for (TicketOrder order : timeoutOrders) {
                try {
                    // 单个订单失败不影响本轮其他订单。
                    Boolean cancelled = cancelAndRelease(order);

                    if (Boolean.TRUE.equals(cancelled)) {
                        log.info(
                                "超时未支付订单已取消并释放库存, orderId={}, ticketId={}",
                                order.getId(),
                                order.getTicketId()
                        );
                    }
                } catch (Exception e) {
                    log.error("释放超时订单失败, orderId={}", order.getId(), e);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    // 只允许待支付订单转为已取消。
    private boolean doCancel(TicketOrder order) {
        return update().setSql("status = 2")
                .eq("id", order.getId())
                .eq("user_id", order.getUserId())
                .eq("status", 0).update();
    }

    private boolean cancelAndRelease(TicketOrder order) {
        Boolean cancelled = transactionTemplate.execute(status -> {
            if (!doCancel(order)) {
                return false;
            }
            releaseStock(order);
            return true;
        });
        return Boolean.TRUE.equals(cancelled);
    }

    // 回补 MySQL 和 Redis 库存并清理购票资格。
    private void releaseStock(TicketOrder order) {
        int updated = ticketStockMapper.update(
                null, new LambdaUpdateWrapper<TicketStock>()
                        .setSql("stock = stock + 1")
                        .eq(TicketStock::getTicketId, order.getTicketId())
        );

        if (updated != 1) {
            throw new BusinessException("数据库库存释放失败");
        }

        releaseRedisReservationAfterCommit(order);

        // 按订单快照退还实际抵扣积分。
        int usedCredits = order.getUsedCredits() == null ? 0 : order.getUsedCredits();
        if (usedCredits > 0) {
            userInfoService.update(new LambdaUpdateWrapper<UserInfo>()
                    .setSql("credits = credits + {0}", usedCredits)
                    .eq(UserInfo::getUserId, order.getUserId()));
            UserInfo info = userInfoService.getById(order.getUserId());
            int balance = info != null && info.getCredits() != null ? info.getCredits() : usedCredits;
            creditLogService.recordLog(order.getUserId(), 3, String.valueOf(order.getId()), usedCredits, balance,
                    "订单取消积分退还 (订单: " + order.getId() + ")");
            log.info("【订单取消退还积分成功】orderId={}, userId={}, returnCredits={}, balance={}",
                    order.getId(), order.getUserId(), usedCredits, balance);
        }

        evictEventDetailCacheAfterCommit(order.getEventId());
    }

    // 事务提交后释放 Redis 预约。
    private void releaseRedisReservationAfterCommit(TicketOrder order) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    Long releaseResult = ticketReservationScript.rollback(order.getTicketId(), order.getUserId());
                    if (releaseResult == null || releaseResult != 1L) {
                        log.warn("【库存释放提示】Redis 预约记录不存在或已释放, orderId={}, ticketId={}, userId={}",
                                order.getId(), order.getTicketId(), order.getUserId());
                    }
                } catch (Exception e) {
                    log.error("【库存释放失败】订单已取消、MySQL 库存已回补，但 Redis 释放失败，需人工核对库存与一人一票资格！"
                                    + "orderId={}, ticketId={}, userId={}",
                            order.getId(), order.getTicketId(), order.getUserId(), e);
                }
            }
        });
    }

    // 事务提交后清理演出详情缓存。
    private void evictEventDetailCacheAfterCommit(Long eventId) {
        if (eventId == null) {
            return;
        }
        String key = RedisConstants.CACHE_EVENT_DETAIL_KEY + eventId;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                stringRedisTemplate.delete(key);
            }
        });
    }
}
