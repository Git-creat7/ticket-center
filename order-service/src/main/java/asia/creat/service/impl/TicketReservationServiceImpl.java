package asia.creat.service.impl;

import asia.creat.common.PageResult;
import asia.creat.common.exception.BusinessException;
import asia.creat.config.TicketStockCacheInitializer;
import asia.creat.dto.PageQuery;
import asia.creat.entity.ReservationTask;
import asia.creat.entity.Ticket;
import asia.creat.entity.TicketOrder;
import asia.creat.entity.TicketReservation;
import asia.creat.entity.TicketStock;
import asia.creat.entity.TicketWaitlist;
import asia.creat.mapper.ReservationTaskMapper;
import asia.creat.mapper.TicketMapper;
import asia.creat.mapper.TicketOrderMapper;
import asia.creat.mapper.TicketReservationMapper;
import asia.creat.mapper.TicketStockMapper;
import asia.creat.mapper.TicketWaitlistMapper;
import asia.creat.service.TicketReservationService;
import asia.creat.utils.RedisConstants;
import asia.creat.utils.RedisIdWorker;
import asia.creat.utils.TicketReservationScript;
import asia.creat.utils.UserHolder;
import asia.creat.vo.TicketReservationVO;
import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TicketReservationServiceImpl implements TicketReservationService {

    private final TicketReservationMapper reservationMapper;
    private final ReservationTaskMapper taskMapper;
    private final TicketMapper ticketMapper;
    private final TicketStockMapper stockMapper;
    private final TicketOrderMapper orderMapper;
    private final TicketWaitlistMapper waitlistMapper;
    private final RedisIdWorker redisIdWorker;
    private final TransactionTemplate transactionTemplate;
    private final TicketReservationScript reservationScript;
    private final TicketStockCacheInitializer stockCacheInitializer;

    @Override
    public Long reserveTicket(Long ticketId, Boolean useCredits, String requestId) {
        Long userId = UserHolder.getUser().getId();
        String idempotencyKey = requestId == null ? UUID.randomUUID().toString() : requestId;
        if (!idempotencyKey.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new BusinessException(400, "预约请求标识格式不正确");
        }
        boolean credits = Boolean.TRUE.equals(useCredits);
        TicketReservation existing = findRequest(userId, idempotencyKey);
        if (existing != null) {
            return checkRequest(existing, ticketId, credits);
        }

        Ticket ticket = ticketMapper.selectById(ticketId);
        if (ticket == null) {
            throw new BusinessException(404, "票档不存在");
        }
        if (!Integer.valueOf(1).equals(ticket.getStatus())) {
            throw new BusinessException("票档已下架");
        }
        TicketStock stock = stockMapper.selectById(ticketId);
        if (stock == null) {
            throw new BusinessException("票档库存信息不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(stock.getBeginTime())) {
            throw new BusinessException("预约尚未开始");
        }
        if (!now.isBefore(stock.getEndTime())) {
            throw new BusinessException("预约已经结束");
        }

        TicketReservation reservation = new TicketReservation();
        reservation.setId(redisIdWorker.nextId("order"));
        reservation.setOrderId(redisIdWorker.nextId("order"));
        reservation.setUserId(userId);
        reservation.setTicketId(ticketId);
        reservation.setRequestId(idempotencyKey);
        reservation.setUseCredits(credits);
        reservation.setPrice(ticket.getPrice());
        reservation.setStatus(TicketReservation.PROCESSING);
        reservation.setReleasePending(false);
        try {
            transactionTemplate.executeWithoutResult(status -> {
                stockMapper.selectForUpdate(ticketId);
                if (waitlistMapper.selectHead(ticketId) != null) {
                    throw new BusinessException("已有候补排队，请加入候补或查看候补记录");
                }
                reservationMapper.insert(reservation);
                taskMapper.add(reservation.getId(), ReservationTask.SEND_ORDER);
            });
        } catch (DuplicateKeyException e) {
            existing = findRequest(userId, idempotencyKey);
            if (existing != null) {
                return checkRequest(existing, ticketId, credits);
            }
            throw new BusinessException("已有有效预约，请查看预约记录", e);
        }

        return reservation.getId();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Long reserveStock(TicketReservation reservation) {
        Long ticketId = reservation.getTicketId();
        stockMapper.selectForUpdate(ticketId);
        TicketWaitlist head = waitlistMapper.selectHead(ticketId);
        if (head != null && !Objects.equals(head.getReservationId(), reservation.getId())
                && !reservationScript.isReserved(ticketId, reservation.getUserId(), reservation.getId())) {
            return 4L;
        }
        Long result = reservationScript.reserve(ticketId, reservation.getUserId(), reservation.getId());
        if (Long.valueOf(3).equals(result)) {
            stockCacheInitializer.initialize(ticketId);
            result = reservationScript.reserve(ticketId, reservation.getUserId(), reservation.getId());
        }
        return result;
    }

    private TicketReservation findRequest(Long userId, String requestId) {
        return reservationMapper.selectOne(new LambdaQueryWrapper<TicketReservation>()
                .eq(TicketReservation::getUserId, userId)
                .eq(TicketReservation::getRequestId, requestId));
    }

    private Long checkRequest(TicketReservation reservation, Long ticketId, boolean useCredits) {
        if (!Objects.equals(reservation.getTicketId(), ticketId)
                || !Objects.equals(reservation.getUseCredits(), useCredits)) {
            throw new BusinessException(409, "同一个预约请求不能修改票档或积分选项");
        }
        return reservation.getId();
    }

    @Override
    public TicketReservationVO getReservation(Long id) {
        TicketReservation reservation = reservationMapper.selectOne(new LambdaQueryWrapper<TicketReservation>()
                .eq(TicketReservation::getId, id)
                .eq(TicketReservation::getUserId, UserHolder.getUser().getId()));
        if (reservation == null) {
            throw new BusinessException(404, "预约不存在");
        }
        return toViews(List.of(reservation)).get(0);
    }

    @Override
    public PageResult<TicketReservationVO> myReservations(PageQuery query) {
        Page<TicketReservation> page = reservationMapper.selectPage(query.toPage(),
                new LambdaQueryWrapper<TicketReservation>()
                        .eq(TicketReservation::getUserId, UserHolder.getUser().getId())
                        .orderByDesc(TicketReservation::getCreateTime)
                        .orderByDesc(TicketReservation::getId));
        return PageResult.of(toViews(page.getRecords()), page.getTotal(), page.getCurrent(), page.getSize());
    }

    private List<TicketReservationVO> toViews(List<TicketReservation> reservations) {
        if (reservations.isEmpty()) {
            return List.of();
        }
        List<Long> ticketIds = reservations.stream().map(TicketReservation::getTicketId).distinct().toList();
        Map<Long, Ticket> tickets = ticketMapper.selectBatchIds(ticketIds).stream()
                .collect(Collectors.toMap(Ticket::getId, ticket -> ticket));
        List<Long> orderIds = reservations.stream().map(TicketReservation::getOrderId)
                .filter(Objects::nonNull).toList();
        Map<Long, TicketOrder> orders = orderIds.isEmpty() ? Map.of()
                : orderMapper.selectBatchIds(orderIds).stream()
                        .collect(Collectors.toMap(TicketOrder::getId, order -> order));
        return reservations.stream().map(reservation -> {
            TicketReservationVO vo = BeanUtil.copyProperties(reservation, TicketReservationVO.class);
            Ticket ticket = tickets.get(reservation.getTicketId());
            if (ticket != null) {
                vo.setTicketTitle(ticket.getTitle());
            }
            vo.setStatusDesc(switch (reservation.getStatus()) {
                case TicketReservation.PROCESSING -> "处理中";
                case TicketReservation.SUCCESS -> "预约成功";
                case TicketReservation.FAILED -> "预约失败";
                case TicketReservation.CANCELLED -> "已取消";
                default -> "未知";
            });
            TicketOrder order = orders.get(reservation.getOrderId());
            if (order != null) {
                vo.setOrderStatus(order.getStatus());
                vo.setPaymentDeadline(order.getCreateTime().plus(RedisConstants.ORDER_TIMEOUT));
            } else {
                vo.setOrderId(null);
            }
            return vo;
        }).toList();
    }

    @Override
    public void failReservation(Long id, String reason) {
        transactionTemplate.executeWithoutResult(status -> {
            // 建单和失败补偿锁同一条预约，只有一种结果能提交。
            TicketReservation reservation = reservationMapper.selectForUpdate(id);
            if (reservation == null || reservation.getStatus() != TicketReservation.PROCESSING) {
                return;
            }
            reservation.setStatus(TicketReservation.FAILED);
            reservation.setFailureReason(reason);
            reservation.setReleasePending(true);
            reservationMapper.updateById(reservation);
            taskMapper.complete(id, ReservationTask.SEND_ORDER);
            taskMapper.add(id, ReservationTask.RELEASE);
        });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseReservation(Long id) {
        // 取消事务提交后的回补需要单独提交。
        TicketReservation reservation = reservationMapper.selectForUpdate(id);
        if (reservation == null || !Boolean.TRUE.equals(reservation.getReleasePending())) {
            taskMapper.complete(id, ReservationTask.RELEASE);
            return;
        }
        Long ticketId = reservation.getTicketId();
        stockMapper.selectForUpdate(ticketId);
        Long result = reservationScript.rollback(ticketId, reservation.getUserId(), id);
        if (Long.valueOf(3).equals(result)) {
            stockCacheInitializer.initialize(ticketId);
            result = reservationScript.rollback(ticketId, reservation.getUserId(), id);
        }
        if (!Long.valueOf(0).equals(result) && !Long.valueOf(1).equals(result)) {
            throw new IllegalStateException("Redis 回补暂不可用");
        }
        reservation.setReleasePending(false);
        reservationMapper.updateById(reservation);
        taskMapper.complete(id, ReservationTask.RELEASE);
    }
}
