package org.example.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.example.domain.StockOperation;

/**
 * 库存操作服务接口
 * 
 * 用途：幂等性控制
 * - 检查操作是否已执行过
 * - 记录操作执行状态
 * - 防止重复操作
 */
public interface IStockOperation extends IService<StockOperation> {
    
    /**
     * 检查操作是否已执行过（幂等性检查）
     * 
     * @param idempotentId 幂等ID
     * @return true: 已执行过；false: 未执行过
     */
    boolean hasExecuted(String idempotentId);

    /**
     * 记录操作（幂等ID + 操作类型）
     * 
     * @param idempotentId 幂等ID
     * @param operateType 操作类型
     * @param operateNum 操作数量
     * @return 操作记录对象
     */
    StockOperation recordOperation(String idempotentId, Integer operateType, Integer operateNum, Long skuId);
}
