package org.example.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.example.domain.Stock;
import org.example.domain.StockOperationLog;
import org.example.mapper.StockMapper;
import org.example.service.IStockOperationLogService;
import org.example.service.IStockService;
import org.example.util.DistributedLockUtil;
import org.example.util.IdempotentUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 库存服务实现 - 高并发版本
 *
 * 核心设计思想：
 * 1. 幂等性控制：使用Redis记录已处理的businessId，防止重复扣减
 * 2. 分布式锁：使用Redisson分布式锁保证同一SKU的并发写安全
 * 3. 乐观锁：结合数据库version字段实现乐观锁，减少锁粒度
 * 4. 事务：保证库存和日志的原子性更新
 * 5. 缓存：使用Redis缓存库存，减少数据库查询
 * 6. 日志：完整记录所有操作，用于审计和补偿
 *
 * 执行流程：
 * 1. 检查幂等性（防重复）
 * 2. 获取分布式锁（防并发冲突）
 * 3. 查询当前库存（带缓存）
 * 4. 验证库存充足
 * 5. 执行扣减操作（带乐观锁）
 * 6. 记录操作日志
 * 7. 更新缓存
 * 8. 发送MQ消息通知
 */
@Slf4j
@Service
public class StockServiceImpl extends ServiceImpl<StockMapper, Stock> implements IStockService {

    private final StockMapper stockMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final IdempotentUtil idempotentUtil;
    private final IStockOperationLogService stockOperationLogService;

    public StockServiceImpl(StockMapper stockMapper,
                            @Autowired(required = false) RedisTemplate<String, Object> redisTemplate,
                            IdempotentUtil idempotentUtil,
                            IStockOperationLogService stockOperationLogService) {
        this.stockMapper = stockMapper;
        this.redisTemplate = redisTemplate;
        this.idempotentUtil = idempotentUtil;
        this.stockOperationLogService = stockOperationLogService;
    }

    private static final String STOCK_CACHE_PREFIX = "stock:cache:";
    // 缓存过期时间（秒）
    private static final long STOCK_CACHE_EXPIRE = 3600;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deductStock(Long skuId, Integer quantity, String businessId, String traceId) throws Exception {
        // ==================== 1. 幂等性检查 ====================
        // 如果该操作已执行过，则直接返回true（幂等）
        if (idempotentUtil.isOperated(businessId, "STOCK_DEDUCT")) {
            log.warn("[幂等性] 库存扣减请求已处理过，businessId={}, skuId={}, traceId={}", 
                     businessId, skuId, traceId);
            return true;
        }

        // ==================== 2. 获取分布式锁 ====================
        // 使用SKU ID作为锁的资源标识，同一SKU的请求串行执行
        String lockKey = "stock:deduct:" + skuId;
        try {
            boolean lockAcquired = DistributedLockUtil.tryLock(lockKey, 10, TimeUnit.SECONDS);
            if (!lockAcquired) {
                log.warn("[获取分布式锁失败] skuId={}, 库存正在被操作，请稍后重试", skuId);
                return false;
            }
            log.debug("[分布式锁] 成功获取库存扣减锁，skuId={}, traceId={}", skuId, traceId);

            // ==================== 3. 查询当前库存 ====================
            Stock stock = getStockWithCache(skuId);
            if (stock == null) {
                log.error("[库存查询失败] skuId={}, traceId={}", skuId, traceId);
                recordOperationLog(skuId, businessId, quantity, 0, 0, "DEDUCT", "FAILED", 
                                   "库存记录不存在", null, traceId);
                return false;
            }

            // ==================== 4. 验证库存充足 ====================
            if (stock.getAvailableStock() < quantity) {
                log.warn("[库存不足] skuId={}, availableStock={}, requiredQuantity={}, traceId={}", 
                         skuId, stock.getAvailableStock(), quantity, traceId);
                recordOperationLog(skuId, businessId, quantity, stock.getAvailableStock(), 
                                   stock.getAvailableStock(), "DEDUCT", "FAILED", 
                                   "库存不足，可用库存：" + stock.getAvailableStock(), null, traceId);
                return false;
            }

            int stockBefore = stock.getAvailableStock();

            // ==================== 5. 执行扣减操作（乐观锁） ====================
            // 使用乐观锁：更新时检查version，防止ABA问题
            int updatedRows = stockMapper.deductStockWithOptimisticLock(
                    skuId,
                    quantity,
                    stock.getVersion()
            );

            if (updatedRows == 0) {
                log.warn("[乐观锁冲突] 库存扣减失败，可能是并发修改，skuId={}, version={}, traceId={}", 
                         skuId, stock.getVersion(), traceId);
                recordOperationLog(skuId, businessId, quantity, stockBefore, stockBefore, 
                                   "DEDUCT", "FAILED", "乐观锁冲突，版本不匹配", null, traceId);
                return false;
            }

            int stockAfter = stockBefore - quantity;
            log.info("[库存扣减成功] skuId={}, quantity={}, stockBefore={}, stockAfter={}, traceId={}", 
                     skuId, quantity, stockBefore, stockAfter, traceId);

            // ==================== 6. 记录操作日志 ====================
            recordOperationLog(skuId, businessId, quantity, stockBefore, stockAfter, 
                               "DEDUCT", "SUCCESS", null, null, traceId);

            // ==================== 7. 标记操作已执行 ====================
            // 在成功扣减后标记，保证幂等性
            idempotentUtil.markAsOperated(businessId, "STOCK_DEDUCT");

            // ==================== 8. 更新缓存 ====================
            refreshStockCache(skuId);

            return true;

        } catch (Exception e) {
            log.error("[库存扣减异常] skuId={}, businessId={}, errorMsg={}, traceId={}", 
                      skuId, businessId, e.getMessage(), traceId, e);
            recordOperationLog(skuId, businessId, quantity, 0, 0, "DEDUCT", "FAILED", 
                               e.getMessage(), null, traceId);
            throw e;
        } finally {
            // ==================== 9. 释放分布式锁 ====================
            DistributedLockUtil.unlock(lockKey);
            log.debug("[分布式锁] 已释放库存扣减锁，skuId={}", skuId);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean compensateStock(Long skuId, Integer quantity, String businessId, 
                                   String compensationReason, String traceId) throws Exception {
        // ==================== 1. 幂等性检查 ====================
        if (idempotentUtil.isOperated(businessId, "STOCK_COMPENSATE")) {
            log.warn("[幂等性] 库存补偿请求已处理过，businessId={}, skuId={}, reason={}, traceId={}", 
                     businessId, skuId, compensationReason, traceId);
            return true;
        }

        // ==================== 2. 获取分布式锁 ====================
        String lockKey = "stock:compensate:" + skuId;
        try {
            boolean lockAcquired = DistributedLockUtil.tryLock(lockKey, 10, TimeUnit.SECONDS);
            if (!lockAcquired) {
                log.warn("[获取分布式锁失败] skuId={}, 库存正在被操作，请稍后重试", skuId);
                return false;
            }

            // ==================== 3. 查询当前库存 ====================
            Stock stock = getStockWithCache(skuId);
            if (stock == null) {
                log.error("[库存查询失败] skuId={}, traceId={}", skuId, traceId);
                return false;
            }

            int stockBefore = stock.getAvailableStock();
            int stockAfter = stockBefore + quantity;

            // ==================== 4. 执行补偿操作（乐观锁） ====================
            int updatedRows = stockMapper.compensateStockWithOptimisticLock(
                    skuId,
                    quantity,
                    stock.getVersion()
            );

            if (updatedRows == 0) {
                log.warn("[库存补偿失败] 乐观锁冲突，skuId={}, reason={}, traceId={}", 
                         skuId, compensationReason, traceId);
                recordOperationLog(skuId, businessId, quantity, stockBefore, stockBefore, 
                                   "COMPENSATION", "FAILED", "乐观锁冲突", compensationReason, traceId);
                return false;
            }

            log.info("[库存补偿成功] skuId={}, quantity={}, stockBefore={}, stockAfter={}, reason={}, traceId={}", 
                     skuId, quantity, stockBefore, stockAfter, compensationReason, traceId);

            // ==================== 5. 记录操作日志 ====================
            recordOperationLog(skuId, businessId, quantity, stockBefore, stockAfter, 
                               "COMPENSATION", "SUCCESS", null, compensationReason, traceId);

            // ==================== 6. 标记操作已执行 ====================
            idempotentUtil.markAsOperated(businessId, "STOCK_COMPENSATE");

            // ==================== 7. 更新缓存 ====================
            refreshStockCache(skuId);

            return true;

        } catch (Exception e) {
            log.error("[库存补偿异常] skuId={}, businessId={}, reason={}, errorMsg={}, traceId={}", 
                      skuId, businessId, compensationReason, e.getMessage(), traceId, e);
            throw e;
        } finally {
            DistributedLockUtil.unlock(lockKey);
        }
    }

    @Override
    public Stock getStockWithCache(Long skuId) {
        String cacheKey = STOCK_CACHE_PREFIX + skuId;

        // ==================== 1. 查询缓存 ====================
        if (redisTemplate != null) {
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null && cached instanceof Stock) {
                log.debug("[缓存命中] skuId={}", skuId);
                return (Stock) cached;
            }
        }

        // ==================== 2. 缓存未命中，查询数据库 ====================
        Stock stock = stockMapper.selectById(skuId);
        if (stock != null) {
            // ==================== 3. 写入缓存 ====================
            updateStockCache(skuId, stock);
        }

        return stock;
    }

    @Override
    public void refreshStockCache(Long skuId) {
        String cacheKey = STOCK_CACHE_PREFIX + skuId;
        // 删除缓存，下次查询时会自动 from 数据库重新加载
        if (redisTemplate != null) {
            redisTemplate.delete(cacheKey);
        }
        log.debug("[缓存刷新] skuId={}", skuId);
    }

    /**
     * 更新库存缓存（提取公共方法，避免重复代码）
     *
     * @param skuId 商品SKU ID
     * @param stock 库存对象
     */
    private void updateStockCache(Long skuId, Stock stock) {
        if (redisTemplate == null) {
            return;
        }
        String cacheKey = STOCK_CACHE_PREFIX + skuId;
        redisTemplate.opsForValue().set(
                cacheKey,
                stock,
                STOCK_CACHE_EXPIRE,
                TimeUnit.SECONDS
        );
        log.debug("[缓存更新] skuId={}", skuId);
    }

    /**
     * 记录库存操作日志
     * @param operationType DEDUCT(扣减) 或 COMPENSATION(补偿)
     * @param compensationReason 补偿原因（仅当operationType为COMPENSATION时需要传入）
     */
    private void recordOperationLog(Long skuId, String businessId, Integer quantity, 
                                    Integer stockBefore, Integer stockAfter, 
                                    String operationType, String status, String errorMessage, 
                                    String compensationReason, String traceId) {
        try {
            StockOperationLog log = StockOperationLog.builder()
                    .businessId(businessId)
                    .skuId(skuId)
                    .operationType(operationType)
                    .quantity(quantity)
                    .stockBefore(stockBefore)
                    .stockAfter(stockAfter)
                    .status(status)
                    .compensationReason(compensationReason)
                    .errorMessage(errorMessage)
                    .traceId(traceId)
                    .createTime(LocalDateTime.now())
                    .updateTime(LocalDateTime.now())
                    .build();
            stockOperationLogService.recordOperation(log);
        } catch (Exception e) {
            log.error("[记录日志失败] businessId={}, errorMsg={}", businessId, e.getMessage(), e);
        }
    }
}
