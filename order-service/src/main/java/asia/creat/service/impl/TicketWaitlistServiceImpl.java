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
import asia.creat.service.TicketWaitlistService;
import asia.creat.utils.RedisConstants;
import asia.creat.utils.RedisIdWorker;
import asia.creat.utils.UserHolder;
import asia.creat.vo.TicketWaitlistVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TicketWaitlistServiceImpl implements TicketWaitlistService {

    private final TicketWaitlistMapper waitlistMapper;
    private final TicketMapper ticketMapper;
    private final TicketStockMapper stockMapper;
    private final TicketReservationMapper reservationMapper;
    private final TicketOrderMapper orderMapper;
    private final ReservationTaskMapper taskMapper;
    private final TransactionTemplate transactionTemplate;
    private final TicketStockCacheInitializer stockCacheInitializer;
    private final StringRedisTemplate redis;
    private final RedisIdWorker idWorker;

    @Override
    public Long join(Long ticketId, Boolean useCredits, String requestId) {
        Long userId = UserHolder.getUser().getId();
        String key = requestId == null ? UUID.randomUUID().toString() : requestId;
        if (!key.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new BusinessException(400, "候补请求标识格式不正确");
        }
        boolean credits = Boolean.TRUE.equals(useCredits);
        return transactionTemplate.execute(status -> {
            TicketStock stock = stockMapper.selectForUpdate(ticketId);
            TicketWaitlist existing = waitlistMapper.selectOne(new LambdaQueryWrapper<TicketWaitlist>()
                    .eq(TicketWaitlist::getUserId, userId).eq(TicketWaitlist::getRequestId, key));
            if (existing != null) {
                if (!Objects.equals(existing.getTicketId(), ticketId)
                        || !Objects.equals(existing.getUseCredits(), credits)) {
                    throw new BusinessException(409, "同一个候补请求不能修改票档或积分选项");
                }
                return existing.getId();
            }
            Ticket ticket = ticketMapper.selectById(ticketId);
            if (!onSale(ticket, stock)) {
                throw new BusinessException("票档不存在、已下架或不在销售时间内");
            }
            if (waitlistMapper.selectUserActive(userId, ticketId) != null) {
                throw new BusinessException("已在候补队列中，请查看候补记录");
            }
            if (hasReservation(userId, ticketId)) {
                throw new BusinessException("已有有效预约或订单，不能重复候补");
            }
            if (availableStock(ticketId) > 0 && waitlistMapper.selectHead(ticketId) == null) {
                throw new BusinessException("仍有余票，请直接预约");
            }
            TicketWaitlist entry = new TicketWaitlist();
            entry.setUserId(userId);
            entry.setTicketId(ticketId);
            entry.setRequestId(key);
            entry.setUseCredits(credits);
            entry.setPrice(ticket.getPrice());
            entry.setStatus(TicketWaitlist.WAITING);
            waitlistMapper.insert(entry);
            return entry.getId();
        });
    }

    private boolean hasReservation(Long userId, Long ticketId) {
        return reservationMapper.selectCount(new LambdaQueryWrapper<TicketReservation>()
                .eq(TicketReservation::getUserId, userId).eq(TicketReservation::getTicketId, ticketId)
                .and(query -> query.in(TicketReservation::getStatus, 0, 1)
                        .or().eq(TicketReservation::getReleasePending, true))) > 0
                || orderMapper.selectCount(new LambdaQueryWrapper<TicketOrder>()
                .eq(TicketOrder::getUserId, userId).eq(TicketOrder::getTicketId, ticketId)
                .in(TicketOrder::getStatus, 0, 1)) > 0;
    }

    @Override
    public void cancel(Long id) {
        Long userId = UserHolder.getUser().getId();
        TicketWaitlist entry = waitlistMapper.selectById(id);
        if (entry == null || !Objects.equals(entry.getUserId(), userId)) {
            throw new BusinessException(404, "候补不存在");
        }
        transactionTemplate.executeWithoutResult(status -> {
            stockMapper.selectForUpdate(entry.getTicketId());
            TicketWaitlist current = waitlistMapper.selectById(id);
            if (current.getStatus() == TicketWaitlist.CANCELLED) {
                return;
            }
            if (current.getStatus() != TicketWaitlist.WAITING) {
                throw new BusinessException("当前状态不能取消候补，已递补请在订单中操作");
            }
            current.setStatus(TicketWaitlist.CANCELLED);
            waitlistMapper.updateById(current);
        });
    }

    @Override
    public TicketWaitlistVO getWaitlist(Long id) {
        TicketWaitlistVO view = waitlistMapper.selectView(id, UserHolder.getUser().getId(),
                RedisConstants.ORDER_TIMEOUT.toSeconds());
        if (view == null) {
            throw new BusinessException(404, "候补不存在");
        }
        setStatusDesc(view);
        return view;
    }

    @Override
    public PageResult<TicketWaitlistVO> myWaitlists(PageQuery query) {
        Page<TicketWaitlistVO> page = waitlistMapper.selectMine(query.toPage(), UserHolder.getUser().getId(),
                RedisConstants.ORDER_TIMEOUT.toSeconds());
        page.getRecords().forEach(this::setStatusDesc);
        return PageResult.of(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }

    private void setStatusDesc(TicketWaitlistVO view) {
        view.setStatusDesc(switch (view.getStatus()) {
            case TicketWaitlist.WAITING -> "排队中";
            case TicketWaitlist.ALLOCATING -> "递补中";
            case TicketWaitlist.ALLOCATED -> "已递补";
            case TicketWaitlist.CANCELLED -> "已取消";
            case TicketWaitlist.EXPIRED -> "已失效";
            default -> "未知";
        });
    }

    @Override
    @Scheduled(fixedDelay = 1000)
    public void processWaitlists() {
        for (Long ticketId : waitlistMapper.selectPendingTicketIds()) {
            try {
                transactionTemplate.executeWithoutResult(status -> promote(ticketId));
            } catch (Exception e) {
                log.warn("候补递补失败，等待重试，ticketId={}", ticketId, e);
            }
        }
    }

    private void promote(Long ticketId) {
        // 入队、取消和分配使用同一票档锁，避免插队或重复分配。
        TicketStock stock = stockMapper.selectForUpdate(ticketId);
        Ticket ticket = ticketMapper.selectById(ticketId);
        boolean salesOpen = onSale(ticket, stock);
        if (!salesOpen) {
            waitlistMapper.update(null, new LambdaUpdateWrapper<TicketWaitlist>()
                    .eq(TicketWaitlist::getTicketId, ticketId)
                    .eq(TicketWaitlist::getStatus, TicketWaitlist.WAITING)
                    .set(TicketWaitlist::getStatus, TicketWaitlist.EXPIRED)
                    .set(TicketWaitlist::getFailureReason, "票档已下架或销售已结束"));
        }
        TicketWaitlist head = waitlistMapper.selectHead(ticketId);
        if (head == null) {
            return;
        }
        if (head.getStatus() == TicketWaitlist.ALLOCATING) {
            if (!finishAllocation(head)) {
                return;
            }
            head = waitlistMapper.selectHead(ticketId);
            if (head == null) {
                return;
            }
        }
        if (!salesOpen) {
            return;
        }
        if (stock.getStock() <= 0 || availableStock(ticketId) <= 0) {
            return;
        }

        TicketReservation reservation = new TicketReservation();
        reservation.setId(idWorker.nextId("order"));
        reservation.setOrderId(idWorker.nextId("order"));
        reservation.setUserId(head.getUserId());
        reservation.setTicketId(ticketId);
        reservation.setRequestId("waitlist-" + head.getId() + "-" + reservation.getId());
        reservation.setUseCredits(head.getUseCredits());
        reservation.setPrice(head.getPrice());
        reservation.setStatus(TicketReservation.PROCESSING);
        reservation.setReleasePending(false);
        reservationMapper.insert(reservation);
        taskMapper.add(reservation.getId(), ReservationTask.SEND_ORDER);
        head.setReservationId(reservation.getId());
        head.setStatus(TicketWaitlist.ALLOCATING);
        waitlistMapper.updateById(head);
    }

    private boolean finishAllocation(TicketWaitlist entry) {
        TicketReservation reservation = reservationMapper.selectById(entry.getReservationId());
        if (reservation == null || reservation.getStatus() == TicketReservation.PROCESSING) {
            return false;
        }
        if (reservation.getStatus() == TicketReservation.FAILED) {
            if (Boolean.TRUE.equals(reservation.getReleasePending())) {
                return false;
            }
            // 失败后仍保留原排队顺序，已支付或主动放弃的订单不回队。
            waitlistMapper.update(null, new LambdaUpdateWrapper<TicketWaitlist>()
                    .eq(TicketWaitlist::getId, entry.getId())
                    .eq(TicketWaitlist::getStatus, TicketWaitlist.ALLOCATING)
                    .set(TicketWaitlist::getStatus, TicketWaitlist.WAITING)
                    .set(TicketWaitlist::getReservationId, null));
        } else {
            entry.setStatus(TicketWaitlist.ALLOCATED);
            waitlistMapper.updateById(entry);
        }
        return true;
    }

    private int availableStock(Long ticketId) {
        stockCacheInitializer.initialize(ticketId);
        String value = redis.opsForValue().get(RedisConstants.ticketStockKey(ticketId));
        if (value == null) {
            throw new IllegalStateException("库存暂不可用");
        }
        return Integer.parseInt(value);
    }

    private boolean onSale(Ticket ticket, TicketStock stock) {
        LocalDateTime now = LocalDateTime.now();
        return ticket != null && stock != null && Integer.valueOf(1).equals(ticket.getStatus())
                && !now.isBefore(stock.getBeginTime()) && now.isBefore(stock.getEndTime());
    }
}
