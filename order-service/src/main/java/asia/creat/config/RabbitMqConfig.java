package asia.creat.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {
    // ---------- 1. 订单异步创建队列与死信配置 ----------
    public static final String ORDER_EXCHANGE = "tc.ticket.order.exchange";
    public static final String ORDER_QUEUE = "tc.ticket.order.queue";
    public static final String ORDER_ROUTING_KEY = "ticket.order.create";

    public static final String DEAD_EXCHANGE = "tc.ticket.order.dlx";
    public static final String DEAD_QUEUE = "tc.ticket.order.dlq";
    public static final String DEAD_ROUTING_KEY = "ticket.order.dead";
    public static final String COMPENSATION_FAILED_EXCHANGE = "tc.ticket.order.compensation.failed.exchange";
    public static final String COMPENSATION_FAILED_QUEUE = "tc.ticket.order.compensation.failed.queue";
    public static final String COMPENSATION_FAILED_ROUTING_KEY = "ticket.order.compensation.failed";

    // ---------- 2. 订单超时关单延时队列与死信配置 (原生 TTL + 死信转发) ----------
    public static final String DELAY_EXCHANGE = "tc.ticket.delay.exchange";
    public static final String DELAY_QUEUE = "tc.ticket.delay.queue";
    public static final String DELAY_ROUTING_KEY = "ticket.order.delay";
    /** 订单超时时间：15 分钟 (毫秒) */
    public static final int ORDER_TTL = 15 * 60 * 1000;

    public static final String CANCEL_EXCHANGE = "tc.ticket.cancel.dlx";
    public static final String CANCEL_QUEUE = "tc.ticket.cancel.queue";
    public static final String CANCEL_ROUTING_KEY = "ticket.order.cancel";

    @Bean
    public DirectExchange orderExchange() {
        return new DirectExchange(ORDER_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange deadExchange() {
        return new DirectExchange(DEAD_EXCHANGE, true, false);
    }

    @Bean
    public Queue orderQueue() {
        return QueueBuilder.durable(ORDER_QUEUE)
                .deadLetterExchange(DEAD_EXCHANGE)
                .deadLetterRoutingKey(DEAD_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue deadQueue() {
        return QueueBuilder.durable(DEAD_QUEUE).build();
    }

    @Bean
    public Binding orderBinding() {
        return BindingBuilder.bind(orderQueue())
                .to(orderExchange())
                .with(ORDER_ROUTING_KEY);
    }

    @Bean
    public Binding deadBinding() {
        return BindingBuilder.bind(deadQueue())
                .to(deadExchange())
                .with(DEAD_ROUTING_KEY);
    }

    @Bean
    public DirectExchange compensationFailedExchange() {
        return new DirectExchange(COMPENSATION_FAILED_EXCHANGE, true, false);
    }

    @Bean
    public Queue compensationFailedQueue() {
        return QueueBuilder.durable(COMPENSATION_FAILED_QUEUE).build();
    }

    @Bean
    public Binding compensationFailedBinding() {
        return BindingBuilder.bind(compensationFailedQueue())
                .to(compensationFailedExchange())
                .with(COMPENSATION_FAILED_ROUTING_KEY);
    }

    @Bean
    public SimpleRabbitListenerContainerFactory deadLetterListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            RabbitTemplate rabbitTemplate) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
                .maxAttempts(3)
                .backOffOptions(1000, 2, 5000)
                .recoverer(new RepublishMessageRecoverer(
                        rabbitTemplate,
                        COMPENSATION_FAILED_EXCHANGE,
                        COMPENSATION_FAILED_ROUTING_KEY))
                .build());
        return factory;
    }

    // ---------- 延时关单队列体系 ----------
    @Bean
    public DirectExchange delayExchange() {
        return new DirectExchange(DELAY_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange cancelExchange() {
        return new DirectExchange(CANCEL_EXCHANGE, true, false);
    }

    // 延时队列中的消息到期后转发到关单队列。
    @Bean
    public Queue delayQueue() {
        return QueueBuilder.durable(DELAY_QUEUE)
                .ttl(ORDER_TTL)
                .deadLetterExchange(CANCEL_EXCHANGE)
                .deadLetterRoutingKey(CANCEL_ROUTING_KEY)
                .build();
    }

    // 关单消费者监听此队列并释放未支付订单的资源。
    @Bean
    public Queue cancelQueue() {
        return QueueBuilder.durable(CANCEL_QUEUE).build();
    }

    @Bean
    public Binding delayBinding() {
        return BindingBuilder.bind(delayQueue())
                .to(delayExchange())
                .with(DELAY_ROUTING_KEY);
    }

    @Bean
    public Binding cancelBinding() {
        return BindingBuilder.bind(cancelQueue())
                .to(cancelExchange())
                .with(CANCEL_ROUTING_KEY);
    }

    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
