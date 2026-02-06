package org.example.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.example.domain.MessageDelivery;

/**
 * 消息投递服务接口
 */
public interface IMessageDeliveryService extends IService<MessageDelivery> {

    /**
     * 保存待投递消息
     * 事务性地保存消息和业务数据
     *
     * @param messageId 消息ID
     * @param messageType 消息类型
     * @param messageContent 消息内容
     * @param targetQueue 目标队列
     * @param traceId 追踪ID
     * @return MessageDelivery
     */
    MessageDelivery savePendingMessage(String messageId, String messageType, 
                                       String messageContent, String targetQueue, String traceId);

    /**
     * 标记消息为已发送
     *
     * @param messageId 消息ID
     */
    void markAsSent(String messageId);

    /**
     * 标记消息为已确认
     *
     * @param messageId 消息ID
     */
    void markAsConfirmed(String messageId);

    /**
     * 标记消息为失败并触发重试
     *
     * @param messageId 消息ID
     * @param errorMessage 错误信息
     */
    void markAsFailedAndRetry(String messageId, String errorMessage);

    /**
     * 获取待重试的消息列表
     *
     * @return 待重试消息列表
     */
    java.util.List<MessageDelivery> getPendingRetryMessages();
}
