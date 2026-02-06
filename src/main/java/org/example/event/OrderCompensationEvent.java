package org.example.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 订单补偿事件
 * 当库存扣减失败或订单取消时，发送该事件以触发补偿操作
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderCompensationEvent {
    /**
     * 消息ID
     */
    private String messageId;

    /**
     * 业务ID（订单ID）
     */
    private String businessId;

    /**
     * SKU ID
     */
    private Long skuId;

    /**
     * 补偿数量
     */
    private Integer quantity;

    /**
     * 补偿原因：PAYMENT_FAILED、ORDER_CANCELLED、STOCK_INSUFFICIENT等
     */
    private String compensationReason;

    /**
     * 追踪ID
     */
    private String traceId;

    /**
     * 事件时间戳
     */
    private Long timestamp;
}
