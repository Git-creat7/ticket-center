package asia.creat.mq;

import asia.creat.config.RabbitMqConfig;
import asia.creat.dto.TicketOrderCancelMessage;
import asia.creat.service.TicketOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 订单超时自动关单消费者：
 * 监听经 TTL 延时缓冲队列（15分钟）死信转发而来的关单消息，
 * 检查订单支付状态，对超时未支付订单自动流转状态为已取消并双向释放 MySQL 与 Redis 库存。
 *
 * 【设计契约】：
 * 延时关单处理若出现异常，记录错误日志，由后台定时扫库任务 releaseTimeoutOrders 负责最终一致性兜底。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketOrderCancelConsumer {

    private final TicketOrderService ticketOrderService;

    @RabbitListener(queues = RabbitMqConfig.CANCEL_QUEUE)
    public void handleCancelOrder(TicketOrderCancelMessage message) {
        if (message == null || message.getOrderId() == null) {
            log.warn("【延时关单队列】接收到空关单消息，忽略处理");
            return;
        }

        Long orderId = message.getOrderId();
        log.info("【延时关单队列】接收到超时关单消息，orderId={}, userId={}, ticketId={}",
                orderId, message.getUserId(), message.getTicketId());
        try {
            ticketOrderService.cancelTimeoutOrder(orderId);
        } catch (Exception e) {
            log.error("【延时关单异常】处理超时订单取消失败，orderId={} (将由定时扫库任务自动兜底)", orderId, e);
        }
    }
}
