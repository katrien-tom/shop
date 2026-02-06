package org.example.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.example.util.TraceIdUtil;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * TraceId 过滤器
 * - 为每个请求自动生成/传递 traceId
 * - 在请求开始时设置 ThreadLocal
 * - 在请求结束时清除 ThreadLocal
 * - 无需在 Controller 中手动管理 traceId
 * 
 * 规范遵循：
 * - Spring 官方建议使用 Filter 管理 ThreadLocal
 * - 日志框架最佳实践
 * - 分布式系统链路追踪标准
 */
@Slf4j
@Component
public class TraceIdFilter extends OncePerRequestFilter {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException {

        String traceId = TraceIdUtil.generateTraceId();
        TraceIdUtil.setTraceId(traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            TraceIdUtil.clearTraceId();
        }
    }
}

