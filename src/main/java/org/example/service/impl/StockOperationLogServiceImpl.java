package org.example.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.example.domain.StockOperationLog;
import org.example.mapper.StockOperationLogMapper;
import org.example.service.IStockOperationLogService;
import org.springframework.stereotype.Service;

/**
 * 库存操作日志服务实现
 */
@Service
public class StockOperationLogServiceImpl extends ServiceImpl<StockOperationLogMapper, StockOperationLog>
        implements IStockOperationLogService {

    @Override
    public void recordOperation(StockOperationLog log) {
        this.save(log);
    }
}
