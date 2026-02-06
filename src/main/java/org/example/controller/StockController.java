package org.example.controller;

import lombok.extern.slf4j.Slf4j;
import org.example.event.StockDeductedEvent;
import org.example.mq.StockEventPublisher;
import org.example.service.IStockService;
import org.example.util.TraceIdUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 库存控制器
 * - 提供库存扣减API
 * - 支持全链路追踪
 * - 返回事务消息操作结果
 */
@Slf4j
@RestController
@RequestMapping("/api/stock")
public class StockController {

    private final IStockService stockService;
    private final StockEventPublisher stockEventPublisher;

    public StockController(IStockService stockService,
                          StockEventPublisher stockEventPublisher) {
        this.stockService = stockService;
        this.stockEventPublisher = stockEventPublisher;
    }

    /**
     * 库存扣减API
     * 执行流程：
     * 1. 生成追踪ID（全链路追踪）
     * 2. 执行库存扣减（含幂等性、分布式锁、乐观锁）
     * 3. 发送库存扣减事件到MQ（事务消息）
     * 4. 返回操作结果
     *
     * @param skuId SKU ID
     * @param quantity 扣减数量
     * @param businessId 业务ID（订单ID）
     * @return 操作结果
     */
    @PostMapping("/deduct")
    public ResponseEntity<Map<String, Object>> deductStock(
            @RequestParam Long skuId,
            @RequestParam Integer quantity,
            @RequestParam String businessId) {

        // ✅ TraceId 由 Filter 自动设置
        String traceId = TraceIdUtil.getTraceId();

        Map<String, Object> response = new HashMap<>();
        response.put("traceId", traceId);

        try {
            log.info("[库存扣减请求] skuId={}, quantity={}, businessId={}, traceId={}",
                     skuId, quantity, businessId, traceId);

            // ==================== 1. 执行库存扣减 ====================
            boolean deductSuccess = stockService.deductStock(
                    skuId,
                    quantity,
                    businessId,
                    traceId
            );

            if (!deductSuccess) {
                log.warn("[库存扣减失败] skuId={}, businessId={}, traceId={}", 
                         skuId, businessId, traceId);
                response.put("code", "DEDUCT_FAILED");
                response.put("message", "库存不足或操作失败");
                return ResponseEntity.ok(response);
            }

            // ==================== 2. 发送库存扣减事件（事务消息） ====================
            // 库存扣减成功后，发送事件到MQ，触发后续业务流程
            StockDeductedEvent event = StockDeductedEvent.builder()
                    .businessId(businessId)
                    .skuId(skuId)
                    .quantity(quantity)
                    .traceId(traceId)
                    .build();

            stockEventPublisher.publishStockDeductedEvent(event);

            // ==================== 3. 返回成功结果 ====================
            response.put("code", "SUCCESS");
            response.put("message", "库存扣减成功，事件已发送");
            log.info("[库存扣减成功] skuId={}, businessId={}, traceId={}", 
                     skuId, businessId, traceId);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("[库存扣减异常] skuId={}, businessId={}, errorMsg={}, traceId={}",
                      skuId, businessId, e.getMessage(), traceId, e);
            response.put("code", "ERROR");
            response.put("message", "系统异常：" + e.getMessage());
            return ResponseEntity.status(500).body(response);

        }
    }

    /**
     * 库存查询API
     *
     * @param skuId SKU ID
     * @return 库存信息
     */
    @GetMapping("/query/{skuId}")
    public ResponseEntity<Map<String, Object>> queryStock(@PathVariable Long skuId) {
        // ✅ TraceId 由 Filter 自动设置
        String traceId = TraceIdUtil.getTraceId();

        Map<String, Object> response = new HashMap<>();
        response.put("traceId", traceId);

        try {
            log.info("[库存查询请求] skuId={}, traceId={}", skuId, traceId);

            // ==================== 1. 查询库存（包含缓存） ====================
            var stock = stockService.getStockWithCache(skuId);

            if (stock == null) {
                response.put("code", "NOT_FOUND");
                response.put("message", "库存记录不存在");
                return ResponseEntity.ok(response);
            }

            // ==================== 2. 返回库存信息 ====================
            response.put("code", "SUCCESS");
            response.put("data", stock);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("[库存查询异常] skuId={}, errorMsg={}, traceId={}",
                      skuId, e.getMessage(), traceId, e);
            response.put("code", "ERROR");
            response.put("message", "系统异常：" + e.getMessage());
            return ResponseEntity.status(500).body(response);

        }
    }

    /**
     * 健康检查API
     * 用于监控系统健康状态
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "UP");
        response.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(response);
    }
}
