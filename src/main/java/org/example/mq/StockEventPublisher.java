package org.example.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.config.RabbitMQConfig;
import org.example.event.StockDeductedEvent;
import org.example.service.IMessageDeliveryService;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 库存事件发布器
 * - 发送库存扣减事件到MQ
 * - 实现事务消息：先保存消息到数据库，再发送到MQ
 * - 若MQ发送失败，消息仍保留在数据库中，后续重试
 */
@Slf4j
@Component
public class StockEventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final IMessageDeliveryService messageDeliveryService;
    private final ObjectMapper objectMapper;

    public StockEventPublisher(@Autowired(required = false) RabbitTemplate rabbitTemplate,
                                IMessageDeliveryService messageDeliveryService,
                                ObjectMapper objectMapper) {
        this.rabbitTemplate = rabbitTemplate;
        this.messageDeliveryService = messageDeliveryService;
        this.objectMapper = objectMapper;
    }

    /**
     * 发布库存扣减事件
     * 
     * 执行流程（事务消息）：
     * 1. 生成全局唯一的messageId
     * 2. 事务性地保存消息到数据库（PENDING状态）
     * 3. 异步发送消息到MQ
     * 4. 监听MQ Broker确认
     * 5. 收到确认后更新数据库状态为CONFIRMED
     * 6. 若发送失败，定时任务会自动重试
     *
     * @param event 库存扣减事件
     */
    public void publishStockDeductedEvent(StockDeductedEvent event) {
        try {
            // ==================== 1. 生成消息ID ====================
            String messageId = UUID.randomUUID().toString();
            event.setMessageId(messageId);
            event.setTimestamp(System.currentTimeMillis());

            // ==================== 2. 事务性保存消息到数据库 ====================
            String messageContent = objectMapper.writeValueAsString(event);
            messageDeliveryService.savePendingMessage(
                    messageId,
                    "STOCK_DEDUCTED",
                    messageContent,
                    RabbitMQConfig.STOCK_DEDUCT_QUEUE,
                    event.getTraceId()
            );
            log.info("[事务消息保存] messageId={}, businessId={}, traceId={}", 
                     messageId, event.getBusinessId(), event.getTraceId());

            // ==================== 3. 发送消息到MQ（如果 RabbitMQ 可用） ====================
            if (rabbitTemplate != null) {
                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.STOCK_DEDUCT_EXCHANGE,
                        RabbitMQConfig.STOCK_DEDUCT_ROUTING_KEY,
                        event,
                        message -> {
                            // 设置消息ID头，用于幂等性验证
                            message.getMessageProperties().setHeader("messageId", messageId);
                            return message;
                        }
                );
                log.info("[库存扣减事件已发送] messageId={}, businessId={}, traceId={}", 
                         messageId, event.getBusinessId(), event.getTraceId());
            } else {
                log.warn("[RabbitMQ 未启用] 库存消息已保存到数据库，等待后续处理, messageId={}, businessId={}", 
                         messageId, event.getBusinessId());
            }

        } catch (Exception e) {
            log.error("[库存事件发布失败] businessId={}, errorMsg={}", 
                      event.getBusinessId(), e.getMessage(), e);
        }
    }
}
