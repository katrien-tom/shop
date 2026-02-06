package org.example.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.example.domain.Stock;

/**
 * 库存服务接口
 * 核心方法：高并发库存扣减
 */
public interface IStockService extends IService<Stock> {

    /**
     * 库存扣减（高并发安全版本）
     * 
     * 核心特性：
     * - 幂等性：使用businessId保证重复请求不重复扣减
     * - 防超卖：使用乐观锁和数据库约束保证库存永不为负
     * - 分布式锁：确保并发写安全
     * - 事务：保证操作原子性
     * - 全链路追踪：支持问题排查
     *
     * @param skuId SKU ID
     * @param quantity 扣减数量
     * @param businessId 业务ID（订单ID或库存操作ID），用于幂等性控制
     * @param traceId 追踪ID（全链路追踪）
     * @return true: 扣减成功；false: 扣减失败（库存不足或已操作）
     * @throws Exception 系统异常
     */
    boolean deductStock(Long skuId, Integer quantity, String businessId, String traceId) throws Exception;

    /**
     * 库存补偿（退款/取消/退货）
     *
     * @param skuId SKU ID
     * @param quantity 补偿数量
     * @param businessId 业务ID
     * @param compensationReason 补偿原因：PAYMENT_FAILED、ORDER_CANCELLED、ORDER_RETURNED、SYSTEM_EXCEPTION
     * @param traceId 追踪ID
     * @return true: 补偿成功；false: 补偿失败
     */
    boolean compensateStock(Long skuId, Integer quantity, String businessId, 
                           String compensationReason, String traceId) throws Exception;

    /**
     * 获取库存（缓存版本）
     * 先查Redis缓存，缓存未命中则查数据库
     *
     * @param skuId SKU ID
     * @return Stock对象
     */
    Stock getStockWithCache(Long skuId);

    /**
     * 刷新库存缓存
     *
     * @param skuId SKU ID
     */
    void refreshStockCache(Long skuId);
}
