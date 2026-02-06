package org.example.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 配置类
 * - 定义可靠消息投递所需的Exchange、Queue、Binding
 * - 支持事务消息、重试机制、补偿处理
 * - 仅在 spring.rabbitmq.listener.simple.enabled=true 时启用
 */
@Configuration
@ConditionalOnProperty(name = "spring.rabbitmq.listener.simple.enabled", havingValue = "true", matchIfMissing = false)
public class RabbitMQConfig {

    // ==================== 库存服务相关 ====================

    // 库存扣减事件交换机
    public static final String STOCK_DEDUCT_EXCHANGE = "stock.deduct.exchange";
    // 库存扣减队列
    public static final String STOCK_DEDUCT_QUEUE = "stock.deduct.queue";
    // 库存扣减routing key
    public static final String STOCK_DEDUCT_ROUTING_KEY = "stock.deduct";

    // 库存扣减死信交换机（用于重试）
    public static final String STOCK_DEDUCT_DLX_EXCHANGE = "stock.deduct.dlx.exchange";
    // 库存扣减死信队列（重试队列）
    public static final String STOCK_DEDUCT_DLX_QUEUE = "stock.deduct.dlx.queue";
    // 库存扣减死信routing key
    public static final String STOCK_DEDUCT_DLX_ROUTING_KEY = "stock.deduct.dlx";

    // ==================== 订单补偿相关 ====================

    // 订单补偿交换机
    public static final String ORDER_COMPENSATION_EXCHANGE = "order.compensation.exchange";
    // 订单补偿队列
    public static final String ORDER_COMPENSATION_QUEUE = "order.compensation.queue";
    // 订单补偿routing key
    public static final String ORDER_COMPENSATION_ROUTING_KEY = "order.compensation";

    // ==================== 库存扣减主队列配置 ====================

    @Bean
    public DirectExchange stockDeductExchange() {
        return new DirectExchange(STOCK_DEDUCT_EXCHANGE, true, false);
    }

    @Bean
    public Queue stockDeductQueue() {
        return QueueBuilder.durable(STOCK_DEDUCT_QUEUE)
                // 设置死信交换机（重试机制）
                .deadLetterExchange(STOCK_DEDUCT_DLX_EXCHANGE)
                .deadLetterRoutingKey(STOCK_DEDUCT_DLX_ROUTING_KEY)
                // 消息超时时间：30秒，超时后进入死信队列
                .ttl(30000)
                .build();
    }

    @Bean
    public Binding stockDeductBinding(Queue stockDeductQueue, DirectExchange stockDeductExchange) {
        return BindingBuilder.bind(stockDeductQueue)
                .to(stockDeductExchange)
                .with(STOCK_DEDUCT_ROUTING_KEY);
    }

    // ==================== 库存扣减死信队列配置（重试队列） ====================

    @Bean
    public DirectExchange stockDeductDlxExchange() {
        return new DirectExchange(STOCK_DEDUCT_DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue stockDeductDlxQueue() {
        return QueueBuilder.durable(STOCK_DEDUCT_DLX_QUEUE)
                // 死信队列重试后回到主队列
                .deadLetterExchange(STOCK_DEDUCT_EXCHANGE)
                .deadLetterRoutingKey(STOCK_DEDUCT_ROUTING_KEY)
                // 重试延迟时间：10秒
                .ttl(10000)
                .build();
    }

    @Bean
    public Binding stockDeductDlxBinding(Queue stockDeductDlxQueue, DirectExchange stockDeductDlxExchange) {
        return BindingBuilder.bind(stockDeductDlxQueue)
                .to(stockDeductDlxExchange)
                .with(STOCK_DEDUCT_DLX_ROUTING_KEY);
    }

    // ==================== 订单补偿队列配置 ====================

    @Bean
    public DirectExchange orderCompensationExchange() {
        return new DirectExchange(ORDER_COMPENSATION_EXCHANGE, true, false);
    }

    @Bean
    public Queue orderCompensationQueue() {
        return QueueBuilder.durable(ORDER_COMPENSATION_QUEUE)
                // 补偿消息不设置超时，需要人工处理
                .build();
    }

    @Bean
    public Binding orderCompensationBinding(Queue orderCompensationQueue, DirectExchange orderCompensationExchange) {
        return BindingBuilder.bind(orderCompensationQueue)
                .to(orderCompensationExchange)
                .with(ORDER_COMPENSATION_ROUTING_KEY);
    }

    // ==================== RabbitTemplate 配置 ====================

    /**
     * 配置RabbitTemplate以支持消息确认和回调
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        // 设置发送者确认回调
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                System.err.println("消息发送失败：" + cause);
            }
        });
        return rabbitTemplate;
    }
}
