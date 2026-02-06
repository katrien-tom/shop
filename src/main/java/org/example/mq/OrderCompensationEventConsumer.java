package org.example.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.config.RabbitMQConfig;
import org.example.event.OrderCompensationEvent;
import org.example.service.IMessageDeliveryService;
import org.example.service.IStockService;
import org.example.util.TraceIdUtil;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * 订单补偿事件消费者
 * - 消费订单补偿事件
 * - 执行库存恢复操作
 * - 实现补偿流程：订单取消/支付失败 -> 触发补偿 -> 库存恢复
 */
@Slf4j
@Component
public class OrderCompensationEventConsumer {

    private final IMessageDeliveryService messageDeliveryService;
    private final IStockService stockService;
    private final ObjectMapper objectMapper;

    public OrderCompensationEventConsumer(IMessageDeliveryService messageDeliveryService,
                                           IStockService stockService,
                                           ObjectMapper objectMapper) {
        this.messageDeliveryService = messageDeliveryService;
        this.stockService = stockService;
        this.objectMapper = objectMapper;
    }

    /**
     * 消费订单补偿事件
     *
     * @param event 订单补偿事件
     * @param messageId 消息ID
     */
    @RabbitListener(queues = RabbitMQConfig.ORDER_COMPENSATION_QUEUE)
    @RabbitHandler
    public void consumeOrderCompensationEvent(OrderCompensationEvent event,
                                               @Header("messageId") String messageId) {
        String traceId = event.getTraceId();
        TraceIdUtil.setTraceId(traceId);
        
        log.info("[消费订单补偿事件] messageId={}, businessId={}, skuId={}, quantity={}, reason={}, traceId={}",
                 messageId, event.getBusinessId(), event.getSkuId(), event.getQuantity(), 
                 event.getCompensationReason(), traceId);

        try {
            // ==================== 1. 业务处理 ====================
            // 执行库存补偿逻辑
            // - 调用库存服务补偿库存（传入补偿原因）
            // - 标记消息为已确认
            
            log.debug("[开始库存补偿] businessId={}, skuId={}, quantity={}, reason={}",
                     event.getBusinessId(), event.getSkuId(), event.getQuantity(),
                     event.getCompensationReason());
            
            boolean compensateSuccess = stockService.compensateStock(
                    event.getSkuId(),
                    event.getQuantity(),
                    event.getBusinessId(),
                    event.getCompensationReason(),
                    traceId
            );
            
            if (!compensateSuccess) {
                log.warn("[库存补偿失败] businessId={}, skuId={}, reason={}",
                        event.getBusinessId(), event.getSkuId(), event.getCompensationReason());
                throw new RuntimeException("库存补偿失败，可能是并发操作或幂等性处理");
            }
            
            log.info("[订单补偿事件处理完成] messageId={}, businessId={}, skuId={}, reason={}, traceId={}",
                     messageId, event.getBusinessId(), event.getSkuId(), 
                     event.getCompensationReason(), traceId);

            // ==================== 2. 标记消息为已确认 ====================
            messageDeliveryService.markAsConfirmed(messageId);

        } catch (Exception e) {
            log.error("[订单补偿事件处理失败] messageId={}, businessId={}, reason={}, errorMsg={}, traceId={}",
                      messageId, event.getBusinessId(), event.getCompensationReason(), 
                      e.getMessage(), traceId, e);
            // 异常会导致消息重新入队或进入死信队列
            throw new RuntimeException("处理订单补偿事件失败", e);
        } finally {
            TraceIdUtil.clearTraceId();
        }
    }
}
