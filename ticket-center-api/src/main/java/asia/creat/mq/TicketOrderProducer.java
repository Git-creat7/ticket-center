package asia.creat.mq;

import asia.creat.config.RabbitMqConfig;
import asia.creat.dto.TicketOrderCancelMessage;
import asia.creat.dto.TicketOrderMessage;
import asia.creat.mapper.TicketOrderMapper;
import asia.creat.utils.TicketReservationScript;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class TicketOrderProducer {

    private final RabbitTemplate rabbitTemplate;
    private final TicketReservationScript ticketReservationScript;
    private final TicketOrderMapper ticketOrderMapper;
    private final TaskScheduler taskScheduler;

    private static final int MAX_CONFIRM_RETRIES = 3;
    private static final Duration CONFIRM_RETRY_DELAY = Duration.ofSeconds(2);

    public void send(TicketOrderMessage message) {
        publish(message, 0, false);
    }

    private void publish(TicketOrderMessage message, int retryCount, boolean deliveryUncertain) {
        CorrelationData correlationData = new CorrelationData(message.getId() + ":" + retryCount);

        correlationData.getFuture().whenComplete((confirm, throwable) -> {
            if (throwable != null) {
                log.error("【RabbitMQ】等待订单消息确认异常（投递结果未知），orderId={}", message.getId(), throwable);
                scheduleRetry(message, retryCount, true);
                return;
            }
            if (correlationData.getReturned() != null) {
                log.error("【RabbitMQ】订单消息无法路由，orderId={}, replyText={}",
                        message.getId(), correlationData.getReturned().getReplyText());
                scheduleRetry(message, retryCount, deliveryUncertain);
                return;
            }
            if (confirm != null && !confirm.isAck()) {
                log.error("【RabbitMQ】Broker 拒收订单消息，orderId={}, 原因={}",
                        message.getId(), confirm.getReason());
                scheduleRetry(message, retryCount, deliveryUncertain);
                return;
            }
            if (confirm == null) {
                log.error("【RabbitMQ】确认结果为空（投递结果未知），orderId={}", message.getId());
                scheduleRetry(message, retryCount, true);
                return;
            }
            log.debug("【RabbitMQ】订单消息成功投递至 Broker, orderId={}", message.getId());
        });

        rabbitTemplate.convertAndSend(
                RabbitMqConfig.ORDER_EXCHANGE,
                RabbitMqConfig.ORDER_ROUTING_KEY,
                message,
                correlationData
        );
    }

    private void scheduleRetry(TicketOrderMessage message, int retryCount, boolean deliveryUncertain) {
        if (retryCount >= MAX_CONFIRM_RETRIES) {
            if (deliveryUncertain) {
                log.error("【RabbitMQ】订单消息确认连续失败，保留 Redis 预扣等待人工核对，orderId={}", message.getId());
            } else {
                rollbackRedisReservation(message.getTicketId(), message.getUserId(), message.getId());
            }
            return;
        }

        int nextRetry = retryCount + 1;
        Instant retryAt = Instant.now().plus(CONFIRM_RETRY_DELAY.multipliedBy(nextRetry));
        try {
            taskScheduler.schedule(() -> retryDelivery(message, nextRetry, deliveryUncertain), retryAt);
        } catch (RuntimeException e) {
            if (deliveryUncertain) {
                log.error("【RabbitMQ】无法安排消息重投，保留 Redis 预扣等待核对，orderId={}", message.getId(), e);
            } else {
                rollbackRedisReservation(message.getTicketId(), message.getUserId(), message.getId());
            }
        }
    }

    private void retryDelivery(TicketOrderMessage message, int retryCount, boolean deliveryUncertain) {
        try {
            if (ticketOrderMapper.selectById(message.getId()) != null) {
                return;
            }
            publish(message, retryCount, deliveryUncertain);
        } catch (Exception e) {
            log.error("【RabbitMQ】重投订单消息失败，orderId={}, retry={}",
                    message.getId(), retryCount, e);
            scheduleRetry(message, retryCount, deliveryUncertain);
        }
    }

    // 发送延时关单消息，丢失时由定时任务兜底。
    public void sendDelayCancelMessage(TicketOrderCancelMessage cancelMessage) {
        CorrelationData correlationData = new CorrelationData("cancel:" + cancelMessage.getOrderId());
        log.info("【RabbitMQ】发送订单超时取消延时消息, orderId={}, TTL={}ms",
                cancelMessage.getOrderId(), RabbitMqConfig.ORDER_TTL);

        correlationData.getFuture().whenComplete((confirm, throwable) -> {
            if (throwable != null || (confirm != null && !confirm.isAck())) {
                log.warn("【RabbitMQ】延时关单消息投递异常/未确认, orderId={} (将由定时扫库任务自动兜底关单)",
                        cancelMessage.getOrderId(), throwable);
            }
        });

        rabbitTemplate.convertAndSend(
                RabbitMqConfig.DELAY_EXCHANGE,
                RabbitMqConfig.DELAY_ROUTING_KEY,
                cancelMessage,
                correlationData
        );
    }

    private void rollbackRedisReservation(Long ticketId, Long userId, Long orderId) {
        try {
            Long result = ticketReservationScript.rollback(ticketId, userId);
            if (result != null && result == 1L) {
                log.info("【补偿成功】Redis 预约库存与资格已成功回滚, orderId={}, ticketId={}, userId={}",
                        orderId, ticketId, userId);
            } else {
                log.warn("【补偿提示】Redis 预约记录不存在或已释放, orderId={}, ticketId={}, userId={}",
                        orderId, ticketId, userId);
            }
        } catch (Exception e) {
            log.error("【严重异常】Redis 预约补偿回滚执行失败，需人工介入！orderId={}, ticketId={}, userId={}",
                    orderId, ticketId, userId, e);
        }
    }
}
