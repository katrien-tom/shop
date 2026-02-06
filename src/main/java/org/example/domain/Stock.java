package org.example.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("stock")
public class Stock{
    /**
     * 主键
     */
    private Long id;
    /**
     * skuID
     */
    private Long skuId;
    /**
     * 总库存
     */
    private Integer totalStock;
    /**
     * 可用库存
     */
    private Integer availableStock;

    /**
     * 已预占库存（下单未支付）
     */
    private Integer lockedStock;
    /**
     * 乐观锁版本号
     */
    private Integer version;
    /**
     * 创建时间
     */
    private LocalDateTime createTime;
    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}
