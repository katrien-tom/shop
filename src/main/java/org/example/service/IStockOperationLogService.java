package org.example.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.example.domain.StockOperationLog;

/**
 * 库存操作日志服务接口
 */
public interface IStockOperationLogService extends IService<StockOperationLog> {
    /**
     * 记录库存操作
     */
    void recordOperation(StockOperationLog log);
}
