package org.example.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.example.domain.StockOperation;
import org.example.mapper.StockOperationMapper;
import org.example.service.IStockOperation;
import org.springframework.stereotype.Service;

/**
 * 库存操作服务实现
 * 
 * 用途：幂等性控制
 * - 基于 idempotentId 的唯一性约束保证幂等性
 * - 支持快速查询操作是否已执行过
 * - 支持查询操作的详细信息
 */
@Service
public class StockOperationServiceImpl extends ServiceImpl<StockOperationMapper, StockOperation> implements IStockOperation {

    @Override
    public boolean hasExecuted(String idempotentId) {
        LambdaQueryWrapper<StockOperation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StockOperation::getIdempotentId, idempotentId);
        return count(wrapper) > 0;
    }

    @Override
    public StockOperation recordOperation(String idempotentId, Integer operateType, Integer operateNum, Long skuId) {
        StockOperation operation = StockOperation.builder()
                .idempotentId(idempotentId)
                .skuId(skuId)
                .operateType(operateType)
                .operateNum(operateNum)
                .operateStatus(0)  // PROCESSING
                .build();
        
        save(operation);
        return operation;
    }
}
