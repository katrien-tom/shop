package org.example.util;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 分布式锁工具类
 * - 基于Redisson实现分布式可重入锁
 * - 支持自动释放和手动释放
 * - 用于订单等关键业务的并发控制
 */
@Component
public class DistributedLockUtil {

    private static DistributedLockUtil instance;
    private RedissonClient redissonClient;

    private static final String LOCK_KEY_PREFIX = "lock:";

    public DistributedLockUtil(@Autowired(required = false) RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
        instance = this;
    }

    public static RLock lockBlocking(String resourceKey) {
        if (instance == null || instance.redissonClient == null) {
            return null;
        }
        return instance.lockBlockingInternal(resourceKey, 30, TimeUnit.SECONDS);
    }

    public static RLock lockBlocking(String resourceKey, long timeout, TimeUnit unit) {
        if (instance == null || instance.redissonClient == null) {
            return null;
        }
        return instance.lockBlockingInternal(resourceKey, timeout, unit);
    }

    private RLock lockBlockingInternal(String resourceKey, long timeout, TimeUnit unit) {
        String lockKey = buildLockKey(resourceKey);
        RLock lock = redissonClient.getLock(lockKey);
        lock.lock(timeout, unit);
        return lock;
    }

    public static boolean tryLock(String resourceKey, long waitTime, TimeUnit unit) {
        if (instance == null || instance.redissonClient == null) {
            return true;
        }
        return instance.tryLockInternal(resourceKey, waitTime, waitTime, unit);
    }

    public static boolean tryLock(String resourceKey, long waitTime, long leaseTime, TimeUnit unit) {
        if (instance == null || instance.redissonClient == null) {
            return true;
        }
        return instance.tryLockInternal(resourceKey, waitTime, leaseTime, unit);
    }

    private boolean tryLockInternal(String resourceKey, long waitTime, long leaseTime, TimeUnit unit) {
        String lockKey = buildLockKey(resourceKey);
        RLock lock = redissonClient.getLock(lockKey);
        try {
            return lock.tryLock(waitTime, leaseTime, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public static void unlock(String resourceKey) {
        if (instance == null || instance.redissonClient == null) {
            return;
        }
        instance.unlockInternal(resourceKey);
    }

    private void unlockInternal(String resourceKey) {
        String lockKey = buildLockKey(resourceKey);
        RLock lock = redissonClient.getLock(lockKey);
        if (lock != null && lock.isLocked()) {
            try {
                lock.unlock();
            } catch (IllegalMonitorStateException e) {
                // 锁已被释放或不属于当前线程，忽略
            }
        }
    }

    public static void unlockByLock(RLock lock) {
        if (lock != null && lock.isLocked()) {
            try {
                lock.unlock();
            } catch (IllegalMonitorStateException e) {
                // 锁已被释放或不属于当前线程，忽略
            }
        }
    }

    private static String buildLockKey(String resourceKey) {
        return LOCK_KEY_PREFIX + resourceKey;
    }

    public static boolean isLocked(String resourceKey) {
        if (instance == null || instance.redissonClient == null) {
            return false;
        }
        String lockKey = buildLockKey(resourceKey);
        RLock lock = instance.redissonClient.getLock(lockKey);
        return lock.isLocked();
    }
}