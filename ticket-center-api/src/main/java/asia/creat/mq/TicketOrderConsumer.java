package asia.creat.mq;

import asia.creat.common.exception.BusinessException;
import asia.creat.config.RabbitMqConfig;
import asia.creat.dto.TicketOrderCancelMessage;
import asia.creat.dto.TicketOrderMessage;
import asia.creat.service.TicketOrderService;
import asia.creat.utils.RedisConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@Slf4j
@RequiredArgsConstructor
public class TicketOrderConsumer {

    private final RedissonClient redissonClient;
    private final TicketOrderService ticketOrderService;
    private final TicketOrderProducer ticketOrderProducer;

    @RabbitListener(queues = RabbitMqConfig.ORDER_QUEUE)
    public void receive(TicketOrderMessage message) {
        // 同一个用户的订单串行处理，避免重复落单
        RLock lock = redissonClient.getLock(
                RedisConstants.LOCK_ORDER_KEY + message.getUserId()
        );

        boolean locked;
        try {
            locked = lock.tryLock(RedisConstants.LOCK_ORDER_WAIT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("等待订单锁被中断，请稍候再试", e);
        }
        if (!locked) {
            throw new BusinessException("获取订单锁超时，请稍候再试");
        }

        try {
            // 通过接口调用，确保 createTicketOrder 的事务生效
            ticketOrderService.createTicketOrder(message);
            log.info("票订单落库成功，orderId={}", message.getId());

            // 发送 15 分钟超时关单延时消息（局部异常隔离保护）
            try {
                TicketOrderCancelMessage cancelMessage = TicketOrderCancelMessage.builder()
                        .orderId(message.getId())
                        .userId(message.getUserId())
                        .ticketId(message.getTicketId())
                        .timestamp(System.currentTimeMillis())
                        .build();
                ticketOrderProducer.sendDelayCancelMessage(cancelMessage);
            } catch (Exception e) {
                // 订单已落库，延时消息失败由定时任务兜底。
                log.error("【延时消息发送失败】订单已成功落库，但投递延时关单消息异常，orderId={} (将由定时扫库任务自动兜底)",
                        message.getId(), e);
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
