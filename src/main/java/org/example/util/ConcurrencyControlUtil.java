package org.example.util;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

/**
 * 并发控制工具类 - 整合幂等性、分布式锁、悲观/乐观锁的统一接口
 * 
 * 设计目的：
 * 1. 提供一致的并发控制API
 * 2. 减少重复的检查代码
 * 3. 支持多种并发控制机制的灵活组合
 * 
 * 使用场景：
 * - 库存扣减：分布式锁 + 幂等性 + 乐观锁
 * - 订单创建：分布式锁 + 幂等性
 * - 支付：分布式锁 + 幂等性 + 业务ID去重
 */
@Slf4j
public class ConcurrencyControlUtil {

    /**
     * 幂等性检查上下文
     */
    public static class IdempotencyCheckResult {
        public final boolean isDuplicate;    // 是否重复请求
        public final String businessId;
        public final String operationType;

        public IdempotencyCheckResult(boolean isDuplicate, String businessId, String operationType) {
            this.isDuplicate = isDuplicate;
            this.businessId = businessId;
            this.operationType = operationType;
        }

        public boolean isFirstExecution() {
            return !isDuplicate;
        }
    }

    /**
     * 分布式锁获取结果
     */
    public static class LockAcquireResult {
        public final boolean success;
        public final String lockKey;
        public final long acquireTime;

        public LockAcquireResult(boolean success, String lockKey) {
            this.success = success;
            this.lockKey = lockKey;
            this.acquireTime = System.currentTimeMillis();
        }
    }

    /**
     * 统一的幂等性检查和分布式锁获取
     * 
     * 使用示例：
     * <pre>
     * IdempotencyCheckResult idempotency = ConcurrencyControlUtil.checkIdempotency(
     *     idempotentUtil, "order:123", "STOCK_DEDUCT"
     * );
     * if (idempotency.isDuplicate) {
     *     // 重复请求，直接返回
     *     return true;
     * }
     * </pre>
     *
     * @param idempotentUtil 幂等性工具类
     * @param businessId 业务ID
     * @param operationType 操作类型
     * @return 幂等性检查结果
     */
    public static IdempotencyCheckResult checkIdempotency(
            IdempotentUtil idempotentUtil,
            String businessId,
            String operationType) {
        
        boolean isDuplicate = idempotentUtil.isOperated(businessId, operationType);
        return new IdempotencyCheckResult(isDuplicate, businessId, operationType);
    }

    /**
     * 标记操作已执行（在幂等性检查之后、业务操作之前调用）
     *
     * @param idempotentUtil 幂等性工具类
     * @param businessId 业务ID
     * @param operationType 操作类型
     * @return 标记成功或失败
     */
    public static boolean markAsExecuted(
            IdempotentUtil idempotentUtil,
            String businessId,
            String operationType) {
        
        return idempotentUtil.markAsOperated(businessId, operationType);
    }

    /**
     * 安全地获取分布式锁并执行业务逻辑
     * 
     * 使用示例：
     * <pre>
     * boolean success = ConcurrencyControlUtil.executeWithLock(
     *     lockKey, 10, TimeUnit.SECONDS,
     *     () -> {
     *         // 业务逻辑
     *         return stockService.deductStock(...);
     *     },
     *     (e) -> {
     *         // 异常处理
     *         log.error("Error executing locked operation", e);
     *     }
     * );
     * </pre>
     *
     * @param lockKey 锁的KEY
     * @param waitTime 等待时间
     * @param unit 时间单位
     * @param operation 要执行的业务逻辑
     * @param exceptionHandler 异常处理回调
     * @return 业务操作的执行结果
     */
    public static boolean executeWithLock(
            String lockKey,
            long waitTime,
            TimeUnit unit,
            LockableOperation operation,
            LockExceptionHandler exceptionHandler) {
        
        try {
            boolean lockAcquired = DistributedLockUtil.tryLock(lockKey, waitTime, unit);
            if (!lockAcquired) {
                log.warn("[获取分布式锁失败] lockKey={}, 资源被占用", lockKey);
                return false;
            }

            log.debug("[获取分布式锁成功] lockKey={}", lockKey);
            return operation.execute();

        } catch (Exception e) {
            if (exceptionHandler != null) {
                exceptionHandler.handle(e);
            }
            return false;
        } finally {
            DistributedLockUtil.unlock(lockKey);
            log.debug("[释放分布式锁] lockKey={}", lockKey);
        }
    }

    /**
     * 锁定的业务操作接口
     */
    @FunctionalInterface
    public interface LockableOperation {
        /**
         * 执行业务逻辑
         * @return 操作是否成功
         */
        boolean execute() throws Exception;
    }

    /**
     * 锁异常处理接口
     */
    @FunctionalInterface
    public interface LockExceptionHandler {
        /**
         * 处理异常
         * @param e 异常对象
         */
        void handle(Exception e);
    }
}
