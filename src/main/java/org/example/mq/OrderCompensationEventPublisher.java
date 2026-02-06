package org.example.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.config.RabbitMQConfig;
import org.example.event.OrderCompensationEvent;
import org.example.service.IMessageDeliveryService;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 订单补偿事件发布器
 * - 发送订单补偿事件到MQ
 * - 用于处理订单取消、支付失败等场景的库存补偿
 * - 实现补偿流程：库存扣减 -> 支付 -> 若支付失败则触发补偿
 */
@Slf4j
@Component
public class OrderCompensationEventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final IMessageDeliveryService messageDeliveryService;
    private final ObjectMapper objectMapper;

    public OrderCompensationEventPublisher(@Autowired(required = false) RabbitTemplate rabbitTemplate,
                                            IMessageDeliveryService messageDeliveryService,
                                            ObjectMapper objectMapper) {
        this.rabbitTemplate = rabbitTemplate;
        this.messageDeliveryService = messageDeliveryService;
        this.objectMapper = objectMapper;
    }

    /**
     * 发布订单补偿事件
     *
     * @param event 订单补偿事件
     */
    public void publishOrderCompensationEvent(OrderCompensationEvent event) {
        try {
            // ==================== 1. 生成消息ID ====================
            String messageId = UUID.randomUUID().toString();
            event.setMessageId(messageId);
            event.setTimestamp(System.currentTimeMillis());

            // ==================== 2. 事务性保存消息到数据库 ====================
            String messageContent = objectMapper.writeValueAsString(event);
            messageDeliveryService.savePendingMessage(
                    messageId,
                    "ORDER_COMPENSATION",
                    messageContent,
                    RabbitMQConfig.ORDER_COMPENSATION_QUEUE,
                    event.getTraceId()
            );
            log.info("[补偿消息保存] messageId={}, businessId={}, reason={}, traceId={}", 
                     messageId, event.getBusinessId(), event.getCompensationReason(), event.getTraceId());

            // ==================== 3. 发送消息到MQ（如果 RabbitMQ 可用） ====================
            if (rabbitTemplate != null) {
                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.ORDER_COMPENSATION_EXCHANGE,
                        RabbitMQConfig.ORDER_COMPENSATION_ROUTING_KEY,
                        event,
                        message -> {
                            message.getMessageProperties().setHeader("messageId", messageId);
                            return message;
                        }
                );
                log.info("[补偿事件已发送] messageId={}, businessId={}, traceId={}", 
                         messageId, event.getBusinessId(), event.getTraceId());
            } else {
                log.warn("[RabbitMQ 未启用] 补偿消息已保存到数据库，等待后续处理, messageId={}, businessId={}", 
                         messageId, event.getBusinessId());
            }

        } catch (Exception e) {
            log.error("[补偿事件发布失败] businessId={}, errorMsg={}", 
                      event.getBusinessId(), e.getMessage(), e);
        }
    }
}
