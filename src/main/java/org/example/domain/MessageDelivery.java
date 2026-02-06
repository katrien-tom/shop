package org.example.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 消息投递记录表
 * - 记录所有发送的消息
 * - 支持消息追踪、重试、补偿
 * - 实现消息不丢失、不重复
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("message_delivery")
public class MessageDelivery {
    /**
     * 主键
     */
    private Long id;

    /**
     * 消息ID（唯一标识，防重复投递）
     */
    private String messageId;

    /**
     * 消息类型：STOCK_DEDUCT、ORDER_PAY、ORDER_COMPENSATION等
     */
    private String messageType;

    /**
     * 消息内容（JSON格式）
     */
    private String messageContent;

    /**
     * 投递状态：PENDING(待投递)、SENT(已发送)、CONFIRMED(已确认)、FAILED(失败)
     */
    private String status;

    /**
     * 投递次数
     */
    private Integer deliveryCount;

    /**
     * 最大重试次数
     */
    private Integer maxRetries;

    /**
     * 下一次重试时间
     */
    private LocalDateTime nextRetryTime;

    /**
     * 目标队列
     */
    private String targetQueue;

    /**
     * 追踪ID
     */
    private String traceId;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}
