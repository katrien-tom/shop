package org.example.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.domain.StockOperation;

@Mapper
public interface StockOperationMapper extends BaseMapper<StockOperation> {
}
