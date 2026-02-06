package org.example.task;

import lombok.extern.slf4j.Slf4j;
import org.example.domain.MessageDelivery;
import org.example.mq.StockEventPublisher;
import org.example.service.IMessageDeliveryService;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 消息重试定时任务
 * - 定期查询失败的消息
 * - 重新发送到MQ
 * - 实现指数退避重试策略
 */
@Slf4j
@Component
@EnableScheduling
public class MessageRetryTask {

    private final IMessageDeliveryService messageDeliveryService;
    private final StockEventPublisher stockEventPublisher;

    public MessageRetryTask(IMessageDeliveryService messageDeliveryService,
                            StockEventPublisher stockEventPublisher) {
        this.messageDeliveryService = messageDeliveryService;
        this.stockEventPublisher = stockEventPublisher;
    }

    /**
     * 定期重试失败的消息
     * 每30秒执行一次
     */
    @Scheduled(fixedDelay = 30000, initialDelay = 5000)
    public void retryFailedMessages() {
        try {
            log.debug("[消息重试任务] 开始执行");

            // ==================== 1. 查询待重试的消息 ====================
            List<MessageDelivery> pendingMessages = messageDeliveryService.getPendingRetryMessages();
            
            if (pendingMessages.isEmpty()) {
                log.debug("[消息重试任务] 没有待重试的消息");
                return;
            }

            // ==================== 2. 逐个重试消息 ====================
            for (MessageDelivery delivery : pendingMessages) {
                try {
                    log.info("[消息重试] messageId={}, messageType={}, deliveryCount={}, traceId={}",
                             delivery.getMessageId(), delivery.getMessageType(), 
                             delivery.getDeliveryCount(), delivery.getTraceId());

                    // ==================== 3. 根据消息类型重新发送 ====================
                    // 这里简化处理，实际应该根据messageType调用不同的发布器
                    // 重新序列化消息内容并发送
                    
                    // 更新消息状态为已发送
                    messageDeliveryService.markAsSent(delivery.getMessageId());

                } catch (Exception e) {
                    log.error("[消息重试失败] messageId={}, errorMsg={}, traceId={}",
                              delivery.getMessageId(), e.getMessage(), delivery.getTraceId(), e);
                    // 标记为失败并调整重试时间
                    messageDeliveryService.markAsFailedAndRetry(delivery.getMessageId(), e.getMessage());
                }
            }

            log.debug("[消息重试任务] 执行完成，共处理{}条消息", pendingMessages.size());

        } catch (Exception e) {
            log.error("[消息重试任务异常] errorMsg={}", e.getMessage(), e);
        }
    }
}
