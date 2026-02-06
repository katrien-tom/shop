package org.example.util;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 全链路追踪工具类
 * - 生成唯一的追踪ID
 * - 支持分布式系统中的链路追踪
 * - 可用于MQ消息、日志等场景
 */
@Component
public class TraceIdUtil {

    // ThreadLocal存储追踪ID
    private static final ThreadLocal<String> TRACE_ID_HOLDER = new ThreadLocal<>();

    /**
     * 生成新的追踪ID
     */
    public static String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 设置追踪ID到ThreadLocal
     */
    public static void setTraceId(String traceId) {
        TRACE_ID_HOLDER.set(traceId);
    }

/**
 * 获取当前的追踪ID
 * - 优先从 ThreadLocal 获取
 * - 如果未设置则返回 null
 * - TraceIdFilter 已保证 traceId 存在
 */
public static String getTraceId() {
    return TRACE_ID_HOLDER.get();
}    /**
     * 清除追踪ID（通常在请求结束时调用）
     */
    public static void clearTraceId() {
        TRACE_ID_HOLDER.remove();
    }
}
