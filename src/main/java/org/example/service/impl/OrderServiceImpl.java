package org.example.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.example.domain.MessageDelivery;
import org.example.domain.Order;
import org.example.mapper.MessageDeliveryMapper;
import org.example.mapper.OrderMapper;
import org.example.service.IOrderService;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrderServiceImpl extends ServiceImpl<OrderMapper, Order> implements IOrderService {

    private final OrderMapper orderMapper;
    private final MessageDeliveryMapper messageDeliveryMapper;

    @Override
    public void saveOrder(Order order) {
        orderMapper.insert(order);
    }

    @Override
    public void updateOrder(Order order) {
        orderMapper.updateById(order);
    }

    @Override
    public Order getById(String orderId) {
        return orderMapper.selectByOrderIdForUpdate(orderId);
    }

    @Override
    public boolean hasCompensationSent(String orderId) {
        LambdaQueryWrapper<MessageDelivery> queryWrapper = new LambdaQueryWrapper<>();
        // 消息内容中包含 orderId，或者我们可以通过 traceId 关联
        // 但最准确的是在 message_delivery 表中增加 business_id 字段，或者解析 message_content
        // 这里为了演示，我们假设 message_content 包含 orderId
        queryWrapper.like(MessageDelivery::getMessageContent, orderId);
        return messageDeliveryMapper.selectCount(queryWrapper) > 0;
    }
}
