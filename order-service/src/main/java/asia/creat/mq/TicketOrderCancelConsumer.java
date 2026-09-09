package asia.creat.mq;

import asia.creat.config.RabbitMqConfig;
import asia.creat.dto.TicketOrderCancelMessage;
import asia.creat.service.TicketOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

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
