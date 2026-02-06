package org.example.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.domain.StockOperationLog;

/**
 * 库存操作日志Mapper
 */
@Mapper
public interface StockOperationLogMapper extends BaseMapper<StockOperationLog> {
}
