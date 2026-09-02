package asia.creat.mq;

import asia.creat.config.RabbitMqConfig;
import asia.creat.dto.TicketOrderMessage;
import asia.creat.mapper.TicketOrderMapper;
import asia.creat.service.TicketOrderService;
import asia.creat.utils.TicketReservationScript;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.TaskScheduler;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketOrderMessagingTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private TicketReservationScript ticketReservationScript;

    @Mock
    private TicketOrderMapper ticketOrderMapper;

    @Mock
    private TaskScheduler taskScheduler;

    @Mock
    private TicketOrderService ticketOrderService;

    @Test
    void unknownPublisherConfirmSchedulesIdempotentRetryWithoutRollback() {
        TicketOrderProducer producer = new TicketOrderProducer(
                rabbitTemplate, ticketReservationScript, ticketOrderMapper, taskScheduler);
        TicketOrderMessage message = message();
        ArgumentCaptor<CorrelationData> correlationCaptor = ArgumentCaptor.forClass(CorrelationData.class);

        producer.send(message);

        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMqConfig.ORDER_EXCHANGE),
                eq(RabbitMqConfig.ORDER_ROUTING_KEY),
                same(message),
                correlationCaptor.capture());
        correlationCaptor.getValue().getFuture()
                .completeExceptionally(new IllegalStateException("confirm lost"));

        ArgumentCaptor<Runnable> retryCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(retryCaptor.capture(), any(Instant.class));
        verify(ticketReservationScript, never()).rollback(message.getTicketId(), message.getUserId());

        when(ticketOrderMapper.selectById(message.getId())).thenReturn(null);
        retryCaptor.getValue().run();

        verify(rabbitTemplate, org.mockito.Mockito.times(2)).convertAndSend(
                eq(RabbitMqConfig.ORDER_EXCHANGE),
                eq(RabbitMqConfig.ORDER_ROUTING_KEY),
                same(message),
                any(CorrelationData.class));
    }

    @Test
    void deadLetterFailureMustPropagateToListenerRetry() {
        TicketOrderDeadConsumer consumer = new TicketOrderDeadConsumer(
                ticketReservationScript, ticketOrderService);
        TicketOrderMessage message = message();
        RuntimeException failure = new RuntimeException("database unavailable");
        when(ticketOrderService.getById(message.getId())).thenThrow(failure);

        RuntimeException thrown = Assertions.assertThrows(
                RuntimeException.class,
                () -> consumer.receiveDeadLetter(message));

        Assertions.assertSame(failure, thrown);
        verify(ticketReservationScript, never()).rollback(message.getTicketId(), message.getUserId());
    }

    @Test
    void publisherNackIsRetriedBeforeReservationRollback() {
        TicketOrderProducer producer = new TicketOrderProducer(
                rabbitTemplate, ticketReservationScript, ticketOrderMapper, taskScheduler);
        TicketOrderMessage message = message();
        ArgumentCaptor<CorrelationData> correlationCaptor = ArgumentCaptor.forClass(CorrelationData.class);

        producer.send(message);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMqConfig.ORDER_EXCHANGE),
                eq(RabbitMqConfig.ORDER_ROUTING_KEY),
                same(message),
                correlationCaptor.capture());
        correlationCaptor.getValue().getFuture()
                .complete(new CorrelationData.Confirm(false, "broker nack"));

        verify(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
        verify(ticketReservationScript, never()).rollback(message.getTicketId(), message.getUserId());
    }

    private TicketOrderMessage message() {
        TicketOrderMessage message = new TicketOrderMessage();
        message.setId(1001L);
        message.setUserId(2001L);
        message.setTicketId(3001L);
        return message;
    }
}
