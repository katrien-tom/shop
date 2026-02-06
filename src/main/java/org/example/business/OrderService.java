package org.example.business;

import lombok.extern.slf4j.Slf4j;
import org.example.domain.Order;
import org.example.event.OrderCompensationEvent;
import org.example.mq.OrderCompensationEventPublisher;
import org.example.service.IOrderService;
import org.example.service.IStockService;
import org.example.util.DistributedLockUtil;
import org.example.util.TraceIdUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 订单服务 - 包含补偿流程的完整业务逻辑
 * <p>
 * 完整流程：
 * 1. 创建订单
 * 2. 扣减库存
 * 3. 支付处理
 * 4. 若支付失败则触发补偿
 */
@Slf4j
@Service
public class OrderService {

    private final IOrderService orderService;
    private final IStockService stockService;
    private final OrderCompensationEventPublisher compensationPublisher;

    public OrderService(IOrderService orderService,
                        IStockService stockService,
                        OrderCompensationEventPublisher compensationPublisher) {
        this.orderService = orderService;
        this.stockService = stockService;
        this.compensationPublisher = compensationPublisher;
    }

    /**
     * 提交订单
     * 由Service层内部生成orderId，仅创建订单和扣减库存，不涉及支付
     * <p>
     * 业务流程：
     * 1. 生成订单ID（业务规则，由Service层负责）
     * 2. 获取分布式锁（防止订单重复创建）
     * 3. 创建订单（订单状态：PENDING）
     * 4. 扣减库存
     * 4.1 成功：订单状态→STOCK_DEDUCTED
     * 4.2 失败：订单状态→CANCELLED，触发补偿
     *
     * @param userId   用户ID
     * @param skuId    SKU ID
     * @param quantity 购买数量
     * @param amount   订单金额
     * @return 订单信息
     * @throws RuntimeException 订单处理失败
     */
    @Transactional(rollbackFor = Exception.class)
    public Order submitOrder(Long userId, Long skuId, Integer quantity, Double amount) {
        // ==================== 第1步：生成订单ID（业务规则） ====================
        String orderId = generateOrderId();
        log.info("[生成订单ID] orderId={}, skuId={}, quantity={}, amount={}",
                orderId, skuId, quantity, amount);

        // ==================== 分布式锁：防止订单重复创建 ====================
        String lockKey = "order:create:" + userId + ":" + skuId;
        boolean lockAcquired = DistributedLockUtil.tryLock(lockKey, 10, TimeUnit.SECONDS);

        if (!lockAcquired) {
            log.warn("[订单提交失败] 订单已在处理中，请勿重复提交, orderId={}", orderId);
            throw new RuntimeException("订单已在处理中，请勿重复提交");
        }

        String traceId = TraceIdUtil.getTraceId();

        log.info("[订单提交开始] orderId={}, skuId={}, quantity={}, amount={}, traceId={}",
                orderId, skuId, quantity, amount, traceId);

        try {
            // ==================== 第2步：检查订单是否已存在（幂等性） ====================
            Order existingOrder = orderService.getById(orderId);
            if (existingOrder != null) {
                log.warn("[订单已存在] orderId={}, status={}", orderId, existingOrder.getStatus());
                return existingOrder;
            }

            // ==================== 第3步：创建订单 ====================
            Order order = Order.builder()
                    .orderId(orderId)
                    .skuId(skuId)
                    .quantity(quantity)
                    .amount(amount)
                    .status("PENDING")      // 待支付
                    .traceId(traceId)
                    .build();

            orderService.saveOrder(order);
            log.info("[订单已创建] orderId={}, status=PENDING", orderId);

            // ==================== 第4步：扣减库存 ====================
            boolean deductSuccess = stockService.deductStock(
                    skuId,
                    quantity,
                    orderId,                // 使用orderId作为幂等性ID
                    traceId
            );

            if (!deductSuccess) {
                log.error("[库存扣减失败] orderId={}, skuId={}, 库存不足", orderId, skuId);
                // 库存扣减失败，订单取消
                order.setStatus("CANCELLED");
                orderService.updateOrder(order);
                throw new RuntimeException("库存扣减失败，订单已取消");
            }

            log.info("[库存扣减成功] orderId={}, skuId={}, quantity={}", orderId, skuId, quantity);

            // ==================== 第5步：更新订单状态为库存已扣减 ====================
            order.setStatus("STOCK_DEDUCTED");
            orderService.updateOrder(order);

            log.info("[订单提交完成] orderId={}, 订单状态→STOCK_DEDUCTED", orderId);
            return order;

        } catch (Exception e) {
            log.error("[订单提交异常] orderId={}, error={}, traceId={}",
                    orderId, e.getMessage(), traceId, e);

            // 异常处理：标记订单为失败状态
            try {
                Order order = orderService.getById(orderId);
                if (order != null && !"CANCELLED".equals(order.getStatus())) {
                    log.warn("[异常补偿] 订单异常，触发补偿, orderId={}", orderId);
                    triggerCompensation(orderId, skuId, quantity, "SYSTEM_EXCEPTION", traceId);
                }
            } catch (Exception compensationError) {
                log.error("[异常补偿失败] orderId={}, error={}", orderId, compensationError.getMessage());
            }

            throw new RuntimeException("订单提交失败: " + e.getMessage(), e);

        } finally {
            // 释放分布式锁
            try {
                DistributedLockUtil.unlock(lockKey);
                log.debug("[释放分布式锁] lockKey={}", lockKey);
            } catch (Exception e) {
                log.warn("[释放分布式锁失败] lockKey={}, error={}", lockKey, e.getMessage());
            }
        }
    }

    /**
     * 支付订单
     * 处理订单支付，支付失败触发补偿
     * <p>
     * 业务流程：
     * 1. 获取分布式锁（防止订单重复支付）
     * 2. 验证订单状态（必须是STOCK_DEDUCTED）
     * 3. 模拟支付处理
     * 4. 根据支付结果更新订单状态
     * 4.1 成功：订单状态→PAID
     * 4.2 失败：触发补偿流程（恢复库存），订单状态→PAYMENT_FAILED
     *
     * @param orderId 订单ID
     * @return 订单信息
     * @throws RuntimeException 支付处理失败
     */
    @Transactional(rollbackFor = Exception.class)
    public Order payOrder(String orderId) {
        // ==================== 分布式锁：防止订单重复支付 ====================
        String lockKey = "order:pay:" + orderId;
        boolean lockAcquired = DistributedLockUtil.tryLock(lockKey, 10, TimeUnit.SECONDS);

        if (!lockAcquired) {
            log.warn("[订单支付失败] 订单正在处理中，请勿重复支付, orderId={}", orderId);
            throw new RuntimeException("订单正在处理中，请勿重复支付");
        }

        String traceId = TraceIdUtil.getTraceId();

        try {
            log.info("[订单支付开始] orderId={}, traceId={}", orderId, traceId);

            // ==================== 第1步：查询订单 ====================
            Order order = orderService.getById(orderId);
            if (order == null) {
                throw new RuntimeException("订单不存在");
            }

            log.debug("[订单查询成功] orderId={}, status={}", orderId, order.getStatus());

            // ==================== 第2步：验证订单状态 ====================
            // 只有STOCK_DEDUCTED或PAYMENT_FAILED状态的订单才能支付
            String status = order.getStatus();
            if (!status.equals("STOCK_DEDUCTED") && !status.equals("PAYMENT_FAILED")) {
                log.warn("[订单状态不允许支付] orderId={}, currentStatus={}", orderId, status);
                throw new RuntimeException("订单状态[" + status + "]不允许支付");
            }

            // ==================== 第3步：模拟支付处理 ====================
            log.info("[模拟支付处理中] orderId={}, amount={}", orderId, order.getAmount());
            boolean paymentSuccess = simulatePayment(orderId, order.getAmount(), traceId);

            // ==================== 第4步：根据支付结果处理 ====================
            if (paymentSuccess) {
                // ✓ 支付成功：订单状态→PAID
                log.info("[支付成功] orderId={}, 订单状态→PAID", orderId);
                order.setStatus("PAID");
                orderService.updateOrder(order);

                // 可选：发送订单确认消息、发货等后续流程
                publishOrderConfirmedEvent(order);

                log.info("[订单支付完成] orderId={}", orderId);
                return order;

            } else {
                // ✗ 支付失败：触发补偿流程，恢复库存
                log.warn("[支付失败] orderId={}, 触发补偿流程", orderId);

                // ==================== 第5步：触发补偿 ====================
                triggerCompensation(
                        orderId,
                        order.getSkuId(),
                        order.getQuantity(),
                        "PAYMENT_FAILED",      // 补偿原因
                        traceId
                );

                // 订单状态→PAYMENT_FAILED
                order.setStatus("PAYMENT_FAILED");
                orderService.updateOrder(order);

                log.warn("[补偿已触发] orderId={}, 库存补偿消息已发送", orderId);
                return order;
            }

        } catch (Exception e) {
            log.error("[订单支付异常] orderId={}, error={}, traceId={}",
                    orderId, e.getMessage(), traceId, e);
            throw new RuntimeException("订单支付失败: " + e.getMessage(), e);

        } finally {
            // 释放分布式锁
            try {
                DistributedLockUtil.unlock(lockKey);
                log.debug("[释放分布式锁] lockKey={}", lockKey);
            } catch (Exception e) {
                log.warn("[释放分布式锁失败] lockKey={}, error={}", lockKey, e.getMessage());
            }
        }
    }

    /**
     * 触发补偿流程
     * <p>
     * 特点：
     * 1. 支持幂等性操作（使用 orderId 作为唯一标识）
     * 2. 异步发送补偿消息
     * 3. 自动重试机制
     *
     * @param orderId  订单ID
     * @param skuId    SKU ID
     * @param quantity 补偿数量
     * @param reason   补偿原因
     * @param traceId  追踪ID
     */
    private void triggerCompensation(String orderId, Long skuId, Integer quantity,
                                     String reason, String traceId) {
        try {
            log.info("[触发补偿] orderId={}, skuId={}, quantity={}, reason={}, traceId={}",
                    orderId, skuId, quantity, reason, traceId);

            // 创建补偿事件
            OrderCompensationEvent event = OrderCompensationEvent.builder()
                    .businessId(orderId)
                    .skuId(skuId)
                    .quantity(quantity)
                    .compensationReason(reason)
                    .traceId(traceId)
                    .timestamp(System.currentTimeMillis())  // 毫秒时间戳
                    .build();

            // 发布补偿事件（异步）
            // 内部会：
            // 1. 生成messageId（基于orderId保证幂等性）
            // 2. 保存到message_delivery表（PENDING）
            // 3. 发送到RabbitMQ
            // 4. 定时任务检测失败消息并重试
            compensationPublisher.publishOrderCompensationEvent(event);

            log.info("[补偿事件已发布] orderId={}, reason={}", orderId, reason);

        } catch (Exception e) {
            log.error("[补偿发布失败] orderId={}, reason={}, error={}",
                    orderId, reason, e.getMessage(), e);
            throw new RuntimeException("补偿事件发布失败: " + e.getMessage(), e);
        }
    }

    /**
     * 模拟支付流程
     * <p>
     * 特点：
     * 1. 支持可配置的失败率
     * 2. 模拟真实的支付延迟
     * 3. 支持支付回调模拟
     *
     * @param orderId 订单ID
     * @param amount  支付金额
     * @param traceId 追踪ID
     * @return 支付是否成功
     */
    private boolean simulatePayment(String orderId, Double amount, String traceId) {
        try {
            // 模拟支付处理延迟 (50-200ms)
            long delay = 50 + (long) (Math.random() * 150);
            Thread.sleep(delay);

            // 模拟支付结果：90%成功，10%失败
            // 可通过配置调整失败率
            boolean success = Math.random() < 0.9;

            log.info("[模拟支付] orderId={}, amount={}, success={}, traceId={}",
                    orderId, amount, success, traceId);

            // 模拟支付回调日志
            if (success) {
                log.info("[支付回调成功] orderId={}, amount={}, transactionNo=PAY-{}-{}",
                        orderId, amount, orderId, System.currentTimeMillis());
            } else {
                log.warn("[支付回调失败] orderId={}, amount={}, reason=insufficient_balance",
                        orderId, amount);
            }

            return success;

        } catch (InterruptedException e) {
            log.error("[模拟支付异常] orderId={}, error={}", orderId, e.getMessage());
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 模拟退款流程
     *
     * @param orderId      订单ID
     * @param refundAmount 退款金额
     * @param traceId      追踪ID
     */
    private void simulateRefund(String orderId, Double refundAmount, String traceId) {
        try {
            // 模拟退款处理延迟 (30-150ms)
            long delay = 30 + (long) (Math.random() * 120);
            Thread.sleep(delay);

            log.info("[模拟退款] orderId={}, refundAmount={}, traceId={}",
                    orderId, refundAmount, traceId);

            // 模拟退款回调（100%成功）
            log.info("[退款回调成功] orderId={}, refundAmount={}, refundNo=REF-{}-{}",
                    orderId, refundAmount, orderId, System.currentTimeMillis());

        } catch (InterruptedException e) {
            log.error("[模拟退款异常] orderId={}, error={}", orderId, e.getMessage());
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 发布订单确认事件
     * （支付成功后的后续流程）
     */
    private void publishOrderConfirmedEvent(Order order) {
        try {
            log.info("[发布订单确认事件] orderId={}", order.getOrderId());
            // 可以在这里发送OrderConfirmedEvent到MQ
            // 触发其他业务流程（如物流、库存锁定、发票等）
        } catch (Exception e) {
            log.error("[订单确认事件发布失败] orderId={}, error={}",
                    order.getOrderId(), e.getMessage());
            // 这里建议不要抛出异常，因为主流程已完成
        }
    }

    /**
     * 手动取消订单
     * 使用分布式锁防止并发，触发补偿流程
     *
     * @param orderId 订单ID
     * @throws RuntimeException 订单取消失败
     */
    @Transactional(rollbackFor = Exception.class)
    public void cancelOrder(String orderId) {
        // ==================== 分布式锁：防止并发取消 ====================
        String lockKey = "order:cancel:" + orderId;
        boolean lockAcquired = DistributedLockUtil.tryLock(lockKey, 10, TimeUnit.SECONDS);

        if (!lockAcquired) {
            log.warn("[订单取消失败] 订单正在处理中，请勿重复取消, orderId={}", orderId);
            throw new RuntimeException("订单正在处理中，请勿重复取消");
        }

        String traceId = TraceIdUtil.getTraceId();

        try {
            log.info("[订单取消] orderId={}, traceId={}", orderId, traceId);

            // 使用悲观锁读取订单，确保状态一致性
            Order order = orderService.getById(orderId);
            if (order == null) {
                throw new RuntimeException("订单不存在");
            }

            // 检查订单状态（只有PENDING和PAID状态的订单才能取消）
            String status = order.getStatus();
            if (!status.equals("PENDING") && !status.equals("PAID")) {
                log.warn("[订单状态不允许取消] orderId={}, currentStatus={}", orderId, status);
                throw new RuntimeException("订单状态[" + status + "]不允许取消");
            }

            // 检查是否已发送过补偿消息（防止重复补偿）
            if (orderService.hasCompensationSent(orderId)) {
                log.warn("[补偿消息已发送] orderId={}, 无需重复取消", orderId);
                return;
            }

            // 如果库存已扣减，则触发补偿
            log.info("[触发库存补偿] orderId={}, skuId={}, quantity={}",
                    orderId, order.getSkuId(), order.getQuantity());

            triggerCompensation(
                    orderId,
                    order.getSkuId(),
                    order.getQuantity(),
                    "ORDER_CANCELLED",
                    traceId
            );

            // 更新订单状态
            order.setStatus("CANCELLED");
            orderService.updateOrder(order);

            log.info("[订单已取消] orderId={}, 补偿已触发", orderId);

        } finally {
            // 释放分布式锁
            try {
                DistributedLockUtil.unlock(lockKey);
            } catch (Exception e) {
                log.warn("[释放分布式锁失败] lockKey={}, error={}", lockKey, e.getMessage());
            }
        }
    }

    /**
     * 订单退货
     * 使用分布式锁防止并发，触发补偿流程（库存增加）
     *
     * @param orderId        订单ID
     * @param returnQuantity 退货数量
     * @throws RuntimeException 退货处理失败
     */
    @Transactional(rollbackFor = Exception.class)
    public void returnOrder(String orderId, Integer returnQuantity) {
        // ==================== 分布式锁：防止并发退货 ====================
        String lockKey = "order:return:" + orderId;
        boolean lockAcquired = DistributedLockUtil.tryLock(lockKey, 10, TimeUnit.SECONDS);

        if (!lockAcquired) {
            log.warn("[订单退货失败] 订单正在处理中，请勿重复退货, orderId={}", orderId);
            throw new RuntimeException("订单正在处理中，请勿重复退货");
        }

        String traceId = TraceIdUtil.getTraceId();

        try {
            log.info("[订单退货] orderId={}, returnQuantity={}, traceId={}",
                    orderId, returnQuantity, traceId);

            // 使用悲观锁读取订单
            Order order = orderService.getById(orderId);
            if (order == null) {
                throw new RuntimeException("订单不存在");
            }

            // 只有DELIVERED（已交付）的订单才能退货
            if (!order.getStatus().equals("DELIVERED")) {
                log.warn("[订单状态不允许退货] orderId={}, currentStatus={}", orderId, order.getStatus());
                throw new RuntimeException("订单状态不允许退货");
            }

            // 检查退货数量有效性
            if (returnQuantity <= 0 || returnQuantity > order.getQuantity()) {
                log.warn("[退货数量不合法] orderId={}, returnQuantity={}, totalQuantity={}",
                        orderId, returnQuantity, order.getQuantity());
                throw new RuntimeException("退货数量不合法");
            }

            // 触发补偿流程（库存增加）
            log.info("[触发库存补偿] orderId={}, skuId={}, returnQuantity={}",
                    orderId, order.getSkuId(), returnQuantity);

            triggerCompensation(
                    orderId,
                    order.getSkuId(),
                    returnQuantity,
                    "ORDER_RETURNED",
                    traceId
            );

            // 更新订单状态
            order.setStatus("RETURNED");
            orderService.updateOrder(order);

            // 模拟退款流程（可选）
            double refundAmount = order.getAmount() * returnQuantity / order.getQuantity();
            simulateRefund(orderId, refundAmount, traceId);

            log.info("[退货已处理] orderId={}, returnQuantity={}, refundAmount={}",
                    orderId, returnQuantity, refundAmount);

        } finally {
            // 释放分布式锁
            try {
                DistributedLockUtil.unlock(lockKey);
            } catch (Exception e) {
                log.warn("[释放分布式锁失败] lockKey={}, error={}", lockKey, e.getMessage());
            }
        }
    }

    /**
     * 生成订单ID（业务规则：Service层负责）
     * 使用UUID生成唯一的订单ID，格式：ORDER_{timestamp}_{uuid}
     * 确保即使在高并发场景下也能生成唯一的ID
     *
     * @return 订单ID
     */
    private String generateOrderId() {
        long timestamp = System.currentTimeMillis();
        String uuid = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String orderId = "ORDER_" + timestamp + "_" + uuid;
        log.debug("[生成订单ID] orderId={}", orderId);
        return orderId;
    }
}
