package org.example.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.example.domain.Order;

@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    /**
     * 根据订单ID查询订单（悲观锁）
     * 
     * @param orderId 订单ID
     * @return 订单对象
     */
    @Select("SELECT * FROM \"order\" WHERE order_id = #{orderId} FOR UPDATE")
    Order selectByOrderIdForUpdate(@Param("orderId") String orderId);
}
