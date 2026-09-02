package asia.creat.mq;

import asia.creat.config.RabbitMqConfig;
import asia.creat.dto.TicketOrderMessage;
import asia.creat.entity.TicketOrder;
import asia.creat.service.TicketOrderService;
import asia.creat.utils.TicketReservationScript;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TicketOrderDeadConsumer {

    private final TicketReservationScript ticketReservationScript;
    private final TicketOrderService ticketOrderService;

    @RabbitListener(
            queues = RabbitMqConfig.DEAD_QUEUE,
            containerFactory = "deadLetterListenerContainerFactory")
    public void receiveDeadLetter(TicketOrderMessage message) {
        log.error("【死信告警】接收到进入死信队列的订单创建消息！orderId={}, userId={}, ticketId={}",
                message.getId(), message.getUserId(), message.getTicketId());

        TicketOrder existingOrder = ticketOrderService.getById(message.getId());
        if (existingOrder != null) {
            log.warn("【死信防御拦截】订单已落库，不回滚 Redis，orderId={}, status={}",
                    message.getId(), existingOrder.getStatus());
            return;
        }

        Long result = ticketReservationScript.rollback(message.getTicketId(), message.getUserId());
        if (result != null && result == 1L) {
            log.info("【死信补偿成功】已回滚 Redis 预约，orderId={}, ticketId={}, userId={}",
                    message.getId(), message.getTicketId(), message.getUserId());
        } else {
            log.warn("【死信补偿提示】预约记录不存在或已回退，orderId={}, ticketId={}, userId={}",
                    message.getId(), message.getTicketId(), message.getUserId());
        }
    }
}
