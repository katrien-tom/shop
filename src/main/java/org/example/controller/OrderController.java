package org.example.controller;

import lombok.extern.slf4j.Slf4j;
import org.example.business.OrderService;
import org.example.domain.Order;
import org.example.util.TraceIdUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 订单控制器
 * 
 * 职责（分层原则）：
 * 1. 接收并验证前端请求参数
 * 2. 调用业务层处理订单（Service层负责生成orderId）
 * 3. 返回结果给前端
 * 
 * 不负责的事情：
 * - orderId生成（由Service层负责 - 业务规则）
 * - 业务逻辑（由Service层负责）
 * 
 * API规范：
 * - POST /api/order/submit - 提交订单（Service生成orderId）
 * - POST /api/order/pay - 支付订单
 * - POST /api/order/cancel - 取消订单
 * - POST /api/order/return - 退货
 */
@Slf4j
@RestController
@RequestMapping("/api/order")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * 提交订单
     * 
     * 业务流程：
     * 1. 验证请求参数
     * 2. 生成追踪ID
     * 3. 调用业务层提交订单（Service层生成orderId）
     * 4. 返回订单信息给前端
     * 
     * 关键设计：orderId由Service层生成，不由Controller生成
     * 原因：orderId是业务规则（幂等性ID），应由Service层负责
     * 
     * 请求体：
     * {
     *     "skuId": 12345,
     *     "quantity": 2,
     *     "amount": 99.99
     * }
     * 
     * 响应：
     * {
     *     "code": "SUCCESS",
     *     "message": "订单提交成功",
     *     "data": {
     *         "orderId": "ORDER_1704067200000_a1b2c3d4",  // Service层生成
     *         "status": "STOCK_DEDUCTED",
     *         "amount": 99.99,
     *         "traceId": "abc123def456..."
     *     }
     * }
     * 
     * @param submitOrderRequest 提交订单请求（包含skuId, quantity, amount）
     * @return 订单信息（orderId由Service层生成）
     */
    @PostMapping("/submit")
    public ResponseEntity<Map<String, Object>> submitOrder(
            @RequestBody SubmitOrderRequest submitOrderRequest) {

        // ==================== 1. 参数验证 ====================
        if (submitOrderRequest == null || 
            submitOrderRequest.getSkuId() == null ||
            submitOrderRequest.getQuantity() == null ||
            submitOrderRequest.getAmount() == null) {
            
            Map<String, Object> response = new HashMap<>();
            response.put("code", "PARAM_ERROR");
            response.put("message", "参数不完整");
            return ResponseEntity.badRequest().body(response);
        }

        // ✅ TraceId 由 Filter 自动设置，无需手动处理
        String traceId = TraceIdUtil.getTraceId();

        Long userId = 666666L;

        Map<String, Object> response = new HashMap<>();
        response.put("traceId", traceId);
        String orderId = null; // 将由Service层生成

        try {
            log.info("[订单提交请求] skuId={}, quantity={}, amount={}, traceId={}",
                     submitOrderRequest.getSkuId(), 
                     submitOrderRequest.getQuantity(), submitOrderRequest.getAmount(), traceId);

            // ==================== 2. 调用业务层（由Service层生成orderId） ====================
            Order order = orderService.submitOrder(
                    userId,
                    submitOrderRequest.getSkuId(),
                    submitOrderRequest.getQuantity(),
                    submitOrderRequest.getAmount()
            );
            
            // Service层已生成orderId
            orderId = order.getOrderId();

            // ==================== 3. 返回成功响应 ====================
            response.put("code", "SUCCESS");
            response.put("message", "订单提交成功");
            response.put("data", new OrderResponse(order));
            
            log.info("[订单提交成功] orderId={}, status={}", orderId, order.getStatus());
            return ResponseEntity.ok(response);

        } catch (RuntimeException e) {
            log.error("[订单提交失败] orderId={}, error={}, traceId={}",
                     orderId, e.getMessage(), traceId, e);
            
            // ==================== 4. 返回失败响应 ====================
            response.put("code", "SUBMIT_FAILED");
            response.put("message", e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    /**
     * 支付订单
     * 
     * 业务流程：
     * 1. 验证orderId（由前端提供，后端验证）
     * 2. 生成追踪ID
     * 3. 调用业务层支付订单
     * 4. 返回支付结果
     * 
     * 请求体：
     * {
     *     "orderId": "550e8400-e29b-41d4-a716-446655440000"
     * }
     * 
     * 响应：
     * {
     *     "code": "SUCCESS",
     *     "message": "支付成功",
     *     "data": {
     *         "orderId": "550e8400-e29b-41d4-a716-446655440000",
     *         "status": "PAID",
     *         "amount": 99.99
     *     }
     * }
     * 
     * @param payOrderRequest 支付订单请求
     * @return 订单信息
     */
    @PostMapping("/pay")
    public ResponseEntity<Map<String, Object>> payOrder(
            @RequestBody PayOrderRequest payOrderRequest) {

        // ==================== 1. 参数验证 ====================
        if (payOrderRequest == null || 
            payOrderRequest.getOrderId() == null || 
            payOrderRequest.getOrderId().isEmpty()) {
            
            Map<String, Object> response = new HashMap<>();
            response.put("code", "PARAM_ERROR");
            response.put("message", "orderId不能为空");
            return ResponseEntity.badRequest().body(response);
        }

        String orderId = payOrderRequest.getOrderId();

        // ✅ TraceId 由 Filter 自动设置
        String traceId = TraceIdUtil.getTraceId();

        Map<String, Object> response = new HashMap<>();
        response.put("traceId", traceId);

        try {
            log.info("[订单支付请求] orderId={}, traceId={}", orderId, traceId);

            // ==================== 2. 调用业务层 ====================
            Order order = orderService.payOrder(orderId);

            // ==================== 3. 返回支付结果 ====================
            response.put("code", "SUCCESS");
            response.put("message", "支付处理完成");
            response.put("data", new OrderResponse(order));
            
            log.info("[订单支付完成] orderId={}, status={}", orderId, order.getStatus());
            return ResponseEntity.ok(response);

        } catch (RuntimeException e) {
            log.error("[订单支付失败] orderId={}, error={}, traceId={}",
                     orderId, e.getMessage(), traceId, e);
            
            response.put("code", "PAY_FAILED");
            response.put("message", e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    /**
     * 取消订单
     * 
     * @param orderId 订单ID
     * @return 操作结果
     */
    @PostMapping("/cancel")
    public ResponseEntity<Map<String, Object>> cancelOrder(
            @RequestParam String orderId) {

        if (orderId == null || orderId.isEmpty()) {
            Map<String, Object> response = new HashMap<>();
            response.put("code", "PARAM_ERROR");
            response.put("message", "orderId不能为空");
            return ResponseEntity.badRequest().body(response);
        }

        // ✅ TraceId 由 Filter 自动设置
        String traceId = TraceIdUtil.getTraceId();

        Map<String, Object> response = new HashMap<>();
        response.put("traceId", traceId);

        try {
            log.info("[订单取消请求] orderId={}, traceId={}", orderId, traceId);

            orderService.cancelOrder(orderId);

            response.put("code", "SUCCESS");
            response.put("message", "订单已取消");
            
            log.info("[订单已取消] orderId={}", orderId);
            return ResponseEntity.ok(response);

        } catch (RuntimeException e) {
            log.error("[订单取消失败] orderId={}, error={}, traceId={}",
                     orderId, e.getMessage(), traceId, e);
            
            response.put("code", "CANCEL_FAILED");
            response.put("message", e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    /**
     * 退货
     * 
     * @param orderId 订单ID
     * @param returnQuantity 退货数量
     * @return 操作结果
     */
    @PostMapping("/return")
    public ResponseEntity<Map<String, Object>> returnOrder(
            @RequestParam String orderId,
            @RequestParam Integer returnQuantity) {

        if (orderId == null || orderId.isEmpty() || returnQuantity == null) {
            Map<String, Object> response = new HashMap<>();
            response.put("code", "PARAM_ERROR");
            response.put("message", "参数不完整");
            return ResponseEntity.badRequest().body(response);
        }

        // ✅ TraceId 由 Filter 自动设置
        String traceId = TraceIdUtil.getTraceId();

        Map<String, Object> response = new HashMap<>();
        response.put("traceId", traceId);

        try {
            log.info("[订单退货请求] orderId={}, returnQuantity={}, traceId={}",
                     orderId, returnQuantity, traceId);

            orderService.returnOrder(orderId, returnQuantity);

            response.put("code", "SUCCESS");
            response.put("message", "退货已处理");
            
            log.info("[订单退货已处理] orderId={}, returnQuantity={}", orderId, returnQuantity);
            return ResponseEntity.ok(response);

        } catch (RuntimeException e) {
            log.error("[订单退货失败] orderId={}, error={}, traceId={}",
                     orderId, e.getMessage(), traceId, e);
            
            response.put("code", "RETURN_FAILED");
            response.put("message", e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    /**
     * 注意：orderId由Service层生成，不在Controller中生成
     * Controller只负责：
     * 1. 接收请求参数
     * 2. 参数验证
     * 3. 调用业务层
     * 4. 返回响应
     */

    // ==================== 内部请求/响应类 ====================

    /**
     * 提交订单请求
     */
    public static class SubmitOrderRequest {
        private Long skuId;
        private Integer quantity;
        private Double amount;

        public SubmitOrderRequest() {}
        public SubmitOrderRequest(Long skuId, Integer quantity, Double amount) {
            this.skuId = skuId;
            this.quantity = quantity;
            this.amount = amount;
        }

        public Long getSkuId() { return skuId; }
        public void setSkuId(Long skuId) { this.skuId = skuId; }

        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }

        public Double getAmount() { return amount; }
        public void setAmount(Double amount) { this.amount = amount; }
    }

    /**
     * 支付订单请求
     */
    public static class PayOrderRequest {
        private String orderId;

        public PayOrderRequest() {}
        public PayOrderRequest(String orderId) {
            this.orderId = orderId;
        }

        public String getOrderId() { return orderId; }
        public void setOrderId(String orderId) { this.orderId = orderId; }
    }

    /**
     * 订单响应
     */
    public static class OrderResponse {
        private String orderId;
        private Long skuId;
        private Integer quantity;
        private Double amount;
        private String status;
        private String traceId;

        public OrderResponse(Order order) {
            this.orderId = order.getOrderId();
            this.skuId = order.getSkuId();
            this.quantity = order.getQuantity();
            this.amount = order.getAmount();
            this.status = order.getStatus();
            this.traceId = order.getTraceId();
        }

        public String getOrderId() { return orderId; }
        public void setOrderId(String orderId) { this.orderId = orderId; }

        public Long getSkuId() { return skuId; }
        public void setSkuId(Long skuId) { this.skuId = skuId; }

        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }

        public Double getAmount() { return amount; }
        public void setAmount(Double amount) { this.amount = amount; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        public String getTraceId() { return traceId; }
        public void setTraceId(String traceId) { this.traceId = traceId; }
    }
}
