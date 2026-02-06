package org.example.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 库存操作日志
 * - 记录所有库存操作（扣减、补偿等）
 * - 用于审计和幂等性验证
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("stock_operation_log")
public class StockOperationLog {
    /**
     * 主键
     */
    private Long id;

    /**
     * 业务ID（订单ID、库存操作ID等）
     */
    private String businessId;

    /**
     * SKU ID
     */
    private Long skuId;

    /**
     * 操作类型：DEDUCT(扣减)、REFUND(退款)、COMPENSATION(补偿)
     */
    private String operationType;

    /**
     * 操作数量
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
     * 操作状态：PENDING(待处理)、SUCCESS(成功)、FAILED(失败)、COMPENSATED(已补偿)
     */
    private String status;

    /**
     * 补偿原因（当operationType=COMPENSATION时有值）
     * PAYMENT_FAILED(支付失败)、ORDER_CANCELLED(取消订单)、ORDER_RETURNED(商品退货)、SYSTEM_EXCEPTION(系统异常)
     */
    private String compensationReason;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 追踪ID（全链路追踪）
     */
    private String traceId;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}
