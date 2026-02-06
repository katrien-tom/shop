package org.example.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.example.domain.Order;

/**
 * 订单服务接口
 * 
 * 实现要求：
 * 1. 基本的CRUD操作
 * 2. 幂等性查询（getById应使用悲观锁 SELECT FOR UPDATE）
 * 3. 补偿消息检查
 */
public interface IOrderService extends IService<Order> {
    
    /**
     * 保存订单
     * 
     * @param order 订单对象
     */
    void saveOrder(Order order);
    
    /**
     * 更新订单
     * 
     * @param order 订单对象
     */
    void updateOrder(Order order);
    
    /**
     * 根据订单ID查询订单
     * 建议使用悲观锁（SELECT FOR UPDATE）来保证读取的一致性
     * 
     * @param orderId 订单ID
     * @return 订单对象，如果不存在返回null
     */
    Order getById(String orderId);
    
    /**
     * 检查是否已发送过补偿消息
     * 用于防止补偿流程的重复执行
     * 
     * 实现建议：
     * - 检查message_delivery表中是否存在该orderId的消息
     * - 状态为非PENDING状态（已发送或已确认）
     * 
     * @param orderId 订单ID
     * @return true: 已发送补偿消息；false: 未发送
     */
    boolean hasCompensationSent(String orderId);
}
