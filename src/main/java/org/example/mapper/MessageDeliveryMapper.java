package org.example.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.domain.MessageDelivery;

/**
 * 消息投递Mapper
 */
@Mapper
public interface MessageDeliveryMapper extends BaseMapper<MessageDelivery> {
}
