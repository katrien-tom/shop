package org.example.util;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 幂等性工具类
 * - 使用Redis实现分布式幂等性验证
 * - 防止重复操作（如库存扣减、订单支付等）
 * - 支持可配置的过期时间
 */
@Component
public class IdempotentUtil {

    private final RedisTemplate<String, Object> redisTemplate;

    // 幂等性Key前缀
    private static final String IDEMPOTENT_KEY_PREFIX = "idempotent:";
    // 默认过期时间（秒）
    private static final long DEFAULT_EXPIRE_TIME = 3600;

    public IdempotentUtil(@Autowired(required = false) RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 检查操作是否已执行过（幂等性检查）
     *
     * @param businessId 业务ID（如订单ID、库存操作ID等）
     * @param operationType 操作类型（如STOCK_DEDUCT、ORDER_PAY等）
     * @return true: 已执行过；false: 首次执行
     */
    public boolean isOperated(String businessId, String operationType) {
        if (redisTemplate == null) {
            return false; // 如果没有Redis，默认未执行过
        }
        String key = buildKey(businessId, operationType);
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    /**
     * 标记操作已执行（记录幂等性凭证）
     * 使用默认过期时间
     *
     * @param businessId 业务ID
     * @param operationType 操作类型
     * @return true: 标记成功；false: 标记失败（可能已存在）
     */
    public boolean markAsOperated(String businessId, String operationType) {
        if (redisTemplate == null) {
            return true; // 如果没有Redis，默认标记成功
        }
        return markAsOperated(businessId, operationType, DEFAULT_EXPIRE_TIME);
    }

    /**
     * 标记操作已执行（记录幂等性凭证）
     *
     * @param businessId 业务ID
     * @param operationType 操作类型
     * @param expireTime 过期时间（秒）
     * @return true: 标记成功；false: 标记失败（可能已存在）
     */
    public boolean markAsOperated(String businessId, String operationType, long expireTime) {
        String key = buildKey(businessId, operationType);
        // 使用setIfAbsent保证原子性：只有当key不存在时才设置，防止重复标记
        Boolean success = redisTemplate.opsForValue().setIfAbsent(
                key,
                System.currentTimeMillis(),
                expireTime,
                TimeUnit.SECONDS
        );
        return Boolean.TRUE.equals(success);
    }

    /**
     * 清除幂等性凭证（通常在操作失败时调用）
     *
     * @param businessId 业务ID
     * @param operationType 操作类型
     */
    public void clearOperated(String businessId, String operationType) {
        String key = buildKey(businessId, operationType);
        redisTemplate.delete(key);
    }

    /**
     * 获取操作执行时间
     *
     * @param businessId 业务ID
     * @param operationType 操作类型
     * @return 执行时间戳（毫秒），或null（如果不存在）
     */
    public Long getOperatedTime(String businessId, String operationType) {
        String key = buildKey(businessId, operationType);
        Object value = redisTemplate.opsForValue().get(key);
        return value != null ? Long.parseLong(value.toString()) : null;
    }

    /**
     * 构建幂等性Key
     */
    private String buildKey(String businessId, String operationType) {
        return IDEMPOTENT_KEY_PREFIX + operationType + ":" + businessId;
    }
}
