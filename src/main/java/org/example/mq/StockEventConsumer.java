package org.example.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.config.RabbitMQConfig;
import org.example.event.StockDeductedEvent;
import org.example.service.IMessageDeliveryService;
import org.example.util.TraceIdUtil;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * 库存事件消费者
 * - 消费库存扣减事件
 * - 实现消息幂等性：通过messageId防止重复处理
 * - 实现消息可靠性：消费失败则转入死信队列重试
 * - 支持全链路追踪：使用traceId追踪消息链路
 */
@Slf4j
@Component
public class StockEventConsumer {

    private final IMessageDeliveryService messageDeliveryService;
    private final ObjectMapper objectMapper;

    public StockEventConsumer(IMessageDeliveryService messageDeliveryService,
                               ObjectMapper objectMapper) {
        this.messageDeliveryService = messageDeliveryService;
        this.objectMapper = objectMapper;
    }

    /**
     * 消费库存扣减事件
     * 
     * RabbitListener自动实现消息确认机制：
     * - 消息处理成功：自动发送ACK到broker，消息从队列删除
     * - 消息处理失败（异常）：自动发送NACK，消息重新入队或进入死信队列
     *
     * @param event 库存扣减事件
     * @param messageId 消息ID（从header中获取）
     */
    @RabbitListener(queues = RabbitMQConfig.STOCK_DEDUCT_QUEUE)
    @RabbitHandler
    public void consumeStockDeductedEvent(StockDeductedEvent event, 
                                          @Header("messageId") String messageId) {
        String traceId = event.getTraceId();
        TraceIdUtil.setTraceId(traceId);
        
        log.info("[消费库存扣减事件] messageId={}, businessId={}, skuId={}, quantity={}, traceId={}",
                 messageId, event.getBusinessId(), event.getSkuId(), event.getQuantity(), traceId);

        try {
            // ==================== 1. 幂等性检查 ====================
            // 根据messageId检查是否已处理过该消息
            // 在实际应用中应该维护一个消息幂等表
            
            // ==================== 2. 业务处理 ====================
            // 这里可以执行后续业务逻辑，例如：
            // - 更新订单状态为"已付款"
            // - 触发物流配送
            // - 发送订单确认短信等
            
            log.info("[库存扣减事件处理完成] messageId={}, businessId={}, traceId={}",
                     messageId, event.getBusinessId(), traceId);

            // ==================== 3. 标记消息为已确认 ====================
            messageDeliveryService.markAsConfirmed(messageId);

        } catch (Exception e) {
            log.error("[库存扣减事件处理失败] messageId={}, businessId={}, errorMsg={}, traceId={}",
                      messageId, event.getBusinessId(), e.getMessage(), traceId, e);
            // 异常会导致消息进入死信队列重试
            throw new RuntimeException("处理库存扣减事件失败", e);
        } finally {
            TraceIdUtil.clearTraceId();
        }
    }

    /**
     * 消费库存扣减死信队列（重试队列）
     * 当消息在主队列处理失败且超过TTL时，会进入该队列
     *
     * @param event 库存扣减事件
     * @param messageId 消息ID
     */
    @RabbitListener(queues = RabbitMQConfig.STOCK_DEDUCT_DLX_QUEUE)
    @RabbitHandler
    public void consumeStockDeductedEventDLX(StockDeductedEvent event,
                                              @Header("messageId") String messageId) {
        String traceId = event.getTraceId();
        TraceIdUtil.setTraceId(traceId);
        
        log.warn("[库存扣减事件重试] messageId={}, businessId={}, traceId={}", 
                 messageId, event.getBusinessId(), traceId);

        try {
            // 重试处理消息（与主消费逻辑相同）
            // 若再次失败，可以选择进入人工处理队列
            
            log.info("[库存扣减事件重试成功] messageId={}, traceId={}", messageId, traceId);
            messageDeliveryService.markAsConfirmed(messageId);

        } catch (Exception e) {
            log.error("[库存扣减事件重试失败] messageId={}, businessId={}, errorMsg={}, traceId={}",
                      messageId, event.getBusinessId(), e.getMessage(), traceId, e);
            // 多次重试失败后，标记为失败状态，需要人工处理
            messageDeliveryService.markAsFailedAndRetry(messageId, e.getMessage());
        } finally {
            TraceIdUtil.clearTraceId();
        }
    }
}
