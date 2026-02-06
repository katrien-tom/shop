package org.example.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 库存扣减事件
 * 当库存扣减成功后，发送该事件到MQ
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockDeductedEvent {
    /**
     * 消息ID（唯一）
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
     * 扣减数量
     */
    private Integer quantity;

    /**
     * 操作前库存
     */
    private Integer stockBefore;

    /**
     * 操作后库存
     */
    private Integer stockAfter;

    /**
     * 追踪ID
     */
    private String traceId;

    /**
     * 事件时间戳
     */
    private Long timestamp;
}
