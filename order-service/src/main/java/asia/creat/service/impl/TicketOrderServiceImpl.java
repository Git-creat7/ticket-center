package asia.creat.service.impl;

import asia.creat.common.PageResult;
import asia.creat.common.exception.BusinessException;
import asia.creat.dto.PageQuery;
import asia.creat.dto.TicketOrderMessage;
import asia.creat.entity.CreditAccount;
import asia.creat.entity.ReservationTask;
import asia.creat.entity.Ticket;
import asia.creat.entity.TicketOrder;
import asia.creat.entity.TicketReservation;
import asia.creat.entity.TicketStock;
import asia.creat.mapper.TicketMapper;
import asia.creat.mapper.TicketOrderMapper;
import asia.creat.mapper.ReservationTaskMapper;
import asia.creat.mapper.TicketReservationMapper;
import asia.creat.mapper.TicketStockMapper;
import asia.creat.service.CreditLogService;
import asia.creat.service.CreditAccountService;
import asia.creat.service.TicketOrderService;
import asia.creat.service.TicketReservationService;
import asia.creat.utils.RedisConstants;
import asia.creat.utils.TicketReservationScript;
import asia.creat.utils.UserHolder;
import asia.creat.vo.TicketOrderVO;
import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final TransactionTemplate transactionTemplate;
    private final CreditAccountService creditAccountService;
    private final CreditLogService creditLogService;
    private final RedissonClient redissonClient;
    private final TicketReservationScript ticketReservationScript;
    private final TicketReservationMapper ticketReservationMapper;
    private final ReservationTaskMapper reservationTaskMapper;
    private final TicketReservationService reservationService;

    // 兜底扫描单轮处理上限。
    private static final int TIMEOUT_SCAN_BATCH_SIZE = 500;

    // 单笔订单的积分抵扣上限，单位与票价一致（分）。
    private static final long MAX_CREDIT_DEDUCTION = 1000L;

    @Override
    @Transactional
    public boolean createTicketOrder(TicketOrderMessage message) {
        TicketReservation reservation = null;
        if (message.getReservationId() != null) {
            reservation = ticketReservationMapper.selectForUpdate(message.getReservationId());
            validateReservationMessage(message, reservation);
            ticketStockMapper.selectForUpdate(reservation.getTicketId());
        }

        TicketOrder existingOrder = getById(message.getId());
        if (existingOrder != null) {
            log.info("【重复投递】订单已存在，跳过落库, orderId={}", message.getId());
            if (reservation != null) {
                validateExistingOrder(existingOrder, reservation);
                markReservationSuccess(reservation, message.getId());
            }
            return false;
        }

        if (reservation != null && !Objects.equals(reservation.getStatus(), TicketReservation.PROCESSING)) {
            return false;
        }

        Long userId = message.getUserId();
        Long ticketId = message.getTicketId();
        Long activeCount = query()
                .eq("user_id", userId)
                .eq("ticket_id", ticketId)
                .in("status", 0, 1)
                .count();
        if (activeCount > 0) {
            if (reservation != null) {
                reservationService.failReservation(reservation.getId(), "该用户已持有此票档的活跃订单");
                return false;
            }
            throw new BusinessException("该用户已持有此票档的活跃订单");
        }

        if (reservation != null) {
            Long result = reservationService.reserveStock(reservation);
            if (Long.valueOf(1).equals(result) || Long.valueOf(2).equals(result)) {
                reservationService.failReservation(reservation.getId(), result == 1L ? "库存不足" : "每个用户限购一张");
                return false;
            }
            if (Long.valueOf(4).equals(result)) {
                reservationService.failReservation(reservation.getId(), "已有候补排队，请加入候补");
                return false;
            }
            if (!Long.valueOf(0).equals(result)) {
                throw new IllegalStateException("Redis 预约状态暂不可用");
            }
        }

        int update = ticketStockMapper.update(
                null,
                new LambdaUpdateWrapper<TicketStock>()
                        .setSql("stock = stock - 1")
                        .eq(TicketStock::getTicketId, ticketId)
                        .gt(TicketStock::getStock, 0)
        );
        if (update != 1) {
            throw new BusinessException("数据库库存不足，稍后重试");
        }

        Ticket ticket = ticketMapper.selectById(ticketId);
        if (ticket == null) {
            throw new BusinessException("票档不存在");
        }

        long originalPrice = reservation != null
                ? reservation.getPrice()
                : message.getPrice() != null ? message.getPrice() : ticket.getPrice();
        Boolean useCredits = reservation != null ? reservation.getUseCredits() : message.getUseCredits();
        int deductedCredits = deductCredits(userId, useCredits, message.getId(), originalPrice);
        long finalPrice = originalPrice - deductedCredits;
        TicketOrder ticketOrder = buildOrder(message, ticket.getEventId(), finalPrice, deductedCredits);

        if (!save(ticketOrder)) {
            throw new BusinessException("保存订单失败");
        }

        if (reservation != null) {
            reservation.setStatus(TicketReservation.SUCCESS);
            reservation.setReleasePending(false);
            ticketReservationMapper.updateById(reservation);
            reservationTaskMapper.complete(reservation.getId(), ReservationTask.SEND_ORDER);
        }

        return true;
    }

    private void validateReservationMessage(TicketOrderMessage message, TicketReservation reservation) {
        if (reservation == null
                || !Objects.equals(reservation.getOrderId(), message.getId())
                || !Objects.equals(reservation.getUserId(), message.getUserId())
                || !Objects.equals(reservation.getTicketId(), message.getTicketId())
                || (message.getUseCredits() != null
                && !Objects.equals(reservation.getUseCredits(), message.getUseCredits()))
                || (message.getPrice() != null
                && !Objects.equals(reservation.getPrice(), message.getPrice()))) {
            throw new BusinessException("预约消息与预约记录不一致");
        }
    }

    private void validateExistingOrder(TicketOrder order, TicketReservation reservation) {
        if (!Objects.equals(order.getUserId(), reservation.getUserId())
                || !Objects.equals(order.getTicketId(), reservation.getTicketId())) {
            throw new BusinessException("预约消息与订单记录不一致");
        }
    }

    private void markReservationSuccess(TicketReservation reservation, Long orderId) {
        if (reservation.getStatus() == TicketReservation.PROCESSING) {
            reservation.setStatus(TicketReservation.SUCCESS);
            reservation.setOrderId(orderId);
            ticketReservationMapper.updateById(reservation);
            reservationTaskMapper.complete(reservation.getId(), ReservationTask.SEND_ORDER);
        }
    }

    private int deductCredits(Long userId, Boolean useCredits, Long orderId, long originalPrice) {
        if (!Boolean.TRUE.equals(useCredits)) {
            return 0;
        }

        CreditAccount account = creditAccountService.lockAccount(userId);
        int availableCredits = account.getCredits();
        int usedCredits = (int) Math.min((long) availableCredits, Math.min(MAX_CREDIT_DEDUCTION, originalPrice));
        if (usedCredits <= 0) {
            return 0;
        }

        boolean deducted = creditAccountService.update(new LambdaUpdateWrapper<CreditAccount>()
                .setSql("credits = credits - {0}", usedCredits)
                .eq(CreditAccount::getUserId, userId)
                .ge(CreditAccount::getCredits, usedCredits));
        if (!deducted) {
            throw new BusinessException("积分抵扣失败");
        }

        int afterBalance = Math.max(0, availableCredits - usedCredits);
        creditLogService.recordLog(
                userId,
                2,
                String.valueOf(orderId),
                -usedCredits,
                afterBalance,
                "购票抵扣立减 (订单: " + orderId + ")"
        );
        log.info("【购票积分抵扣成功】orderId={}, usedCredits={}, 抵扣金额={}分, 实付金额={}分",
                orderId, usedCredits, usedCredits, originalPrice - usedCredits);
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
        if (baseMapper.pay(orderId, userId, RedisConstants.ORDER_TIMEOUT.toSeconds()) != 1) {
            throw new BusinessException("订单不存在、已超时或状态不允许支付");
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
        TicketReservation reservation = ticketReservationMapper.selectByOrderIdForUpdate(orderId);
        boolean cancelled = doCancel(order);
        if (!cancelled) {
            throw new BusinessException("订单状态不允许取消");
        }
        releaseStock(order, reservation);
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

        Map<Long, Ticket> ticketMap = ticketIds.isEmpty() ? Map.of()
                : ticketMapper.selectBatchIds(ticketIds).stream()
                        .collect(Collectors.toMap(Ticket::getId, t -> t, (a, b) -> a));

        List<TicketOrderVO> voList = orders.stream().map(order -> {
            TicketOrderVO vo = BeanUtil.copyProperties(order, TicketOrderVO.class);
            Ticket ticket = ticketMap.get(order.getTicketId());
            if (ticket != null) {
                vo.setTicketTitle(ticket.getTitle());
                vo.setEventName(ticket.getEventName());
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
            List<TicketOrder> timeoutOrders = baseMapper.findExpired(
                    RedisConstants.ORDER_TIMEOUT.toSeconds(), TIMEOUT_SCAN_BATCH_SIZE);
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
            TicketReservation reservation = ticketReservationMapper.selectByOrderIdForUpdate(order.getId());
            if (baseMapper.cancelExpired(order.getId(), RedisConstants.ORDER_TIMEOUT.toSeconds()) != 1) {
                return false;
            }
            releaseStock(order, reservation);
            return true;
        });
        return Boolean.TRUE.equals(cancelled);
    }

    // 回补 MySQL 和 Redis 库存并清理购票资格。
    private void releaseStock(TicketOrder order, TicketReservation reservation) {
        int updated = ticketStockMapper.update(
                null, new LambdaUpdateWrapper<TicketStock>()
                        .setSql("stock = stock + 1")
                        .eq(TicketStock::getTicketId, order.getTicketId())
        );

        if (updated != 1) {
            throw new BusinessException("数据库库存释放失败");
        }

        if (reservation != null) {
            reservation.setStatus(TicketReservation.CANCELLED);
            reservation.setReleasePending(true);
            ticketReservationMapper.updateById(reservation);
            reservationTaskMapper.add(reservation.getId(), ReservationTask.RELEASE);
            releaseReservationAfterCommit(reservation);
        } else {
            releaseRedisReservationAfterCommit(order);
        }

        // 按订单快照退还实际抵扣积分。
        int usedCredits = order.getUsedCredits() == null ? 0 : order.getUsedCredits();
        if (usedCredits > 0) {
            CreditAccount account = creditAccountService.lockAccount(order.getUserId());
            boolean returned = creditAccountService.update(new LambdaUpdateWrapper<CreditAccount>()
                    .setSql("credits = credits + {0}", usedCredits)
                    .eq(CreditAccount::getUserId, order.getUserId()));
            if (!returned) {
                throw new BusinessException("积分退还失败");
            }
            int balance = account.getCredits() + usedCredits;
            creditLogService.recordLog(order.getUserId(), 3, String.valueOf(order.getId()), usedCredits, balance,
                    "订单取消积分退还 (订单: " + order.getId() + ")");
            log.info("【订单取消退还积分成功】orderId={}, userId={}, returnCredits={}, balance={}",
                    order.getId(), order.getUserId(), usedCredits, balance);
        }
    }

    // 事务提交后释放 Redis 预约。
    private void releaseReservationAfterCommit(TicketReservation reservation) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    reservationService.releaseReservation(reservation.getId());
                } catch (Exception e) {
                    log.error("【库存释放失败】订单已取消，等待预约恢复任务重试，reservationId={}", reservation.getId(), e);
                }
            }
        });
    }

    private void releaseRedisReservationAfterCommit(TicketOrder order) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    Long releaseResult = ticketReservationScript.rollback(order.getTicketId(), order.getUserId());
                    if (releaseResult == null || releaseResult != 1L) {
                        log.warn("【库存释放提示】Redis 预约记录不存在或已释放, orderId={}", order.getId());
                    }
                } catch (Exception e) {
                    log.error("【库存释放失败】订单已取消，Redis 释放失败，orderId={}", order.getId(), e);
                }
            }
        });
    }

}
