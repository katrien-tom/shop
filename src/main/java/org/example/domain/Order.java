package org.example.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 订单实体类
 * 
 * 订单状态流转：
 * - PENDING: 待提交库存
 * - STOCK_DEDUCTED: 库存已扣减，待支付
 * - PAID: 已支付，待发货
 * - PAYMENT_FAILED: 支付失败
 * - SHIPPED: 已发货
 * - DELIVERED: 已交付
 * - RETURNED: 已退货
 * - CANCELLED: 已取消
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order {
    
    /**
     * 主键ID
     */
    private Long id;
    
    /**
     * 订单ID（业务唯一标识）
     */
    private String orderId;
    
    /**
     * SKU ID
     */
    private Long skuId;
    
    /**
     * 购买数量
     */
    private Integer quantity;
    
    /**
     * 订单金额
     */
    private Double amount;
    
    /**
     * 订单状态
     * PENDING: 待提交库存
     * STOCK_DEDUCTED: 库存已扣减，待支付
     * PAID: 已支付，待发货
     * PAYMENT_FAILED: 支付失败
     * SHIPPED: 已发货
     * DELIVERED: 已交付
     * RETURNED: 已退货
     * CANCELLED: 已取消
     */
    private String status;
    
    /**
     * 追踪ID（用于分布式链路追踪）
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
