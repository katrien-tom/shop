package org.example.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 库存扣减记录表
 * 
 * 用途：幂等性控制 + 操作追踪
 * - 主要用于保证库存操作的幂等性
 * - 使用 idempotent_id（唯一约束）防止重复操作
 * - 记录操作的关键状态
 * 
 * 与 StockOperationLog 的区别：
 * - StockOperation：关键业务数据，幂等性保证，数据量较少
 * - StockOperationLog：详细审计日志，包含前后库存值，数据量大
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("stock_operation")
public class StockOperation {
    
    /**
     * 主键
     */
    private Long id;

    /**
     * 全局唯一幂等ID（业务ID）
     * 用于防止重复操作，对应订单ID或库存操作ID
     */
    private String idempotentId;

    /**
     * SKU ID
     */
    private Long skuId;

    /**
     * 操作类型
     * 1=PRE_DEDUCT(预扣减)
     * 2=FORMAL_DEDUCT(正式扣减)
     * 3=STOCK_RELEASE(库存释放)
     */
    private Integer operateType;

    /**
     * 操作状态
     * 0=PROCESSING(处理中)
     * 1=SUCCESS(成功)
     * 2=FAILED(失败)
     */
    private Integer operateStatus;

    /**
     * 操作数量
     */
    private Integer operateNum;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 备注
     */
    private String remark;
}
