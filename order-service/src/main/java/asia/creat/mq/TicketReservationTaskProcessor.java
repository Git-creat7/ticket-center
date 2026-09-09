package asia.creat.mq;

import asia.creat.dto.TicketOrderMessage;
import asia.creat.entity.ReservationTask;
import asia.creat.entity.TicketReservation;
import asia.creat.mapper.ReservationTaskMapper;
import asia.creat.mapper.TicketReservationMapper;
import asia.creat.service.TicketReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class TicketReservationTaskProcessor {

    private final ReservationTaskMapper taskMapper;
    private final TicketReservationMapper reservationMapper;
    private final TicketReservationService reservationService;
    private final TicketOrderProducer orderProducer;
    private final TransactionTemplate transactionTemplate;

    @Scheduled(fixedDelay = 1000)
    public void processTasks() {
        List<ReservationTask> tasks = taskMapper.findDueTasks();
        for (ReservationTask task : tasks) {
            if (taskMapper.claim(task.getId()) != 1) {
                continue;
            }
            try {
                if (task.getType() == ReservationTask.SEND_ORDER) {
                    transactionTemplate.executeWithoutResult(status -> processOrder(task));
                } else if (task.getType() == ReservationTask.RELEASE) {
                    reservationService.releaseReservation(task.getReservationId());
                } else {
                    taskMapper.complete(task.getReservationId(), task.getType());
                }
            } catch (Exception e) {
                taskMapper.recordError(task.getId(), shorten(e.getMessage()));
                log.warn("预约恢复任务处理失败，taskId={}, reservationId={}",
                        task.getId(), task.getReservationId(), e);
            }
        }
    }

    private void processOrder(ReservationTask task) {
        // 重试和取消锁同一条预约，防止取消后又重新预扣。
        TicketReservation reservation = reservationMapper.selectForUpdate(task.getReservationId());
        if (reservation == null || reservation.getStatus() != TicketReservation.PROCESSING) {
            taskMapper.complete(task.getReservationId(), ReservationTask.SEND_ORDER);
            return;
        }

        Long result = reservationService.reserveStock(reservation);
        if (Long.valueOf(1).equals(result)) {
            reservationService.failReservation(reservation.getId(), "库存不足");
            return;
        }
        if (Long.valueOf(2).equals(result)) {
            reservationService.failReservation(reservation.getId(), "每个用户限购一张");
            return;
        }
        if (Long.valueOf(4).equals(result)) {
            reservationService.failReservation(reservation.getId(), "已有候补排队，请加入候补");
            return;
        }
        if (!Long.valueOf(0).equals(result)) {
            throw new IllegalStateException("Redis 预约状态暂不可用");
        }

        TicketOrderMessage message = new TicketOrderMessage();
        message.setId(reservation.getOrderId());
        message.setReservationId(reservation.getId());
        message.setUserId(reservation.getUserId());
        message.setTicketId(reservation.getTicketId());
        message.setUseCredits(reservation.getUseCredits());
        message.setPrice(reservation.getPrice());
        orderProducer.send(message);
    }

    private String shorten(String message) {
        if (message == null || message.isBlank()) {
            return "未知异常";
        }
        return message.length() > 255 ? message.substring(0, 255) : message;
    }
}
