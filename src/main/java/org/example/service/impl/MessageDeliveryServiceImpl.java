package org.example.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.example.domain.MessageDelivery;
import org.example.mapper.MessageDeliveryMapper;
import org.example.service.IMessageDeliveryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 消息投递服务实现
 * 
 * 核心功能：
 * 1. 事务性消息保存：保证消息和业务操作原子性
 * 2. 消息状态机：PENDING -> SENT -> CONFIRMED/FAILED
 * 3. 自动重试：失败消息自动重试，支持指数退避
 * 4. 消息幂等：使用messageId保证消息不重复投递
 */
@Slf4j
@Service
public class MessageDeliveryServiceImpl extends ServiceImpl<MessageDeliveryMapper, MessageDelivery>
        implements IMessageDeliveryService {

    // 最大重试次数
    private static final int MAX_RETRIES = 5;
    // 初始重试延迟（秒）
    private static final int INITIAL_RETRY_DELAY = 30;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MessageDelivery savePendingMessage(String messageId, String messageType,
                                               String messageContent, String targetQueue, String traceId) {
        MessageDelivery delivery = MessageDelivery.builder()
                .messageId(messageId)
                .messageType(messageType)
                .messageContent(messageContent)
                .targetQueue(targetQueue)
                .traceId(traceId)
                .status("PENDING")
                .deliveryCount(0)
                .maxRetries(MAX_RETRIES)
                .nextRetryTime(LocalDateTime.now())
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();

        this.save(delivery);
        log.info("[消息保存] messageId={}, messageType={}, traceId={}", messageId, messageType, traceId);
        return delivery;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markAsSent(String messageId) {
        MessageDelivery delivery = this.getById(messageId);
        if (delivery != null) {
            delivery.setStatus("SENT");
            delivery.setDeliveryCount(delivery.getDeliveryCount() + 1);
            delivery.setUpdateTime(LocalDateTime.now());
            this.updateById(delivery);
            log.info("[消息已发送] messageId={}, deliveryCount={}", messageId, delivery.getDeliveryCount());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markAsConfirmed(String messageId) {
        MessageDelivery delivery = this.getById(messageId);
        if (delivery != null) {
            delivery.setStatus("CONFIRMED");
            delivery.setUpdateTime(LocalDateTime.now());
            this.updateById(delivery);
            log.info("[消息已确认] messageId={}", messageId);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markAsFailedAndRetry(String messageId, String errorMessage) {
        MessageDelivery delivery = this.getById(messageId);
        if (delivery != null) {
            delivery.setErrorMessage(errorMessage);

            // 如果超过最大重试次数，则标记为失败
            if (delivery.getDeliveryCount() >= delivery.getMaxRetries()) {
                delivery.setStatus("FAILED");
                log.error("[消息投递失败，超过最大重试次数] messageId={}, deliveryCount={}, maxRetries={}, errorMsg={}",
                          messageId, delivery.getDeliveryCount(), delivery.getMaxRetries(), errorMessage);
            } else {
                // 否则安排下一次重试，使用指数退避策略
                long delaySeconds = INITIAL_RETRY_DELAY * (long) Math.pow(2, delivery.getDeliveryCount());
                delivery.setNextRetryTime(LocalDateTime.now().plusSeconds(delaySeconds));
                log.warn("[消息重试安排] messageId={}, nextRetryTime={}秒后, deliveryCount={}/{}",
                         messageId, delaySeconds, delivery.getDeliveryCount(), delivery.getMaxRetries());
            }

            delivery.setUpdateTime(LocalDateTime.now());
            this.updateById(delivery);
        }
    }

    @Override
    public List<MessageDelivery> getPendingRetryMessages() {
        // 查询所有状态不是CONFIRMED的消息，且nextRetryTime <= 当前时间，且重试次数未超限
        return this.lambdaQuery()
                .in(MessageDelivery::getStatus, "PENDING", "SENT")
                .le(MessageDelivery::getNextRetryTime, LocalDateTime.now())
                .apply("delivery_count < max_retries")  // SQL条件：deliveryCount < maxRetries
                .list();
    }
}
