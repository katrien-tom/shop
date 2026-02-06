package org.example;

import lombok.extern.slf4j.Slf4j;
import org.example.domain.Stock;
import org.example.service.IStockService;
import org.example.util.IdempotentUtil;
import org.example.util.TraceIdUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 高并发库存系统集成测试
 * - 测试单线程扣减
 * - 测试多线程并发扣减
 * - 测试幂等性
 * - 测试库存缓存
 */
@Slf4j
@SpringBootTest
class StockServiceIntegrationTest {

    @Autowired
    private IStockService stockService;

    @Autowired
    private IdempotentUtil idempotentUtil;

    /**
     * 测试单线程库存扣减
     */
    @Test
    void testSingleThreadDeduct() throws Exception {
        Long skuId = 1001L;
        Integer quantity = 10;
        String businessId = "ORDER_TEST_001";
        String traceId = TraceIdUtil.generateTraceId();

        log.info("====== 测试单线程库存扣减 ======");
        log.info("skuId={}, quantity={}, businessId={}, traceId={}", 
                 skuId, quantity, businessId, traceId);

        // 第一次扣减（应该成功）
        boolean result1 = stockService.deductStock(skuId, quantity, businessId, traceId);
        log.info("第一次扣减结果: {}", result1);
        assert result1 : "第一次扣减应该成功";

        // 第二次扣减（应该返回true，因为幂等）
        boolean result2 = stockService.deductStock(skuId, quantity, businessId, traceId);
        log.info("第二次扣减结果（幂等）: {}", result2);
        assert result2 : "幂等性测试应该返回true";

        // 查询库存
        Stock stock = stockService.getStockWithCache(skuId);
        log.info("当前库存: availableStock={}, version={}", 
                 stock.getAvailableStock(), stock.getVersion());
    }

    /**
     * 测试并发扣减（100个线程并发）
     * 验证库存永不为负数
     */
    @Test
    void testConcurrentDeduct() throws Exception {
        Long skuId = 1002L;
        Integer quantityPerThread = 5;
        int threadCount = 100;

        log.info("====== 测试并发库存扣减 ======");
        log.info("初始库存查询...");
        Stock initialStock = stockService.getStockWithCache(skuId);
        Integer initialAvailableStock = initialStock.getAvailableStock();
        log.info("初始可用库存: {}", initialAvailableStock);

        ExecutorService executorService = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        // 启动100个线程并发扣减
        for (int i = 0; i < threadCount; i++) {
            final int threadNo = i;
            executorService.execute(() -> {
                try {
                    String businessId = "ORDER_CONCURRENT_" + threadNo;
                    String traceId = TraceIdUtil.generateTraceId();
                    
                    boolean result = stockService.deductStock(
                            skuId,
                            quantityPerThread,
                            businessId,
                            traceId
                    );
                    
                    if (result) {
                        successCount.incrementAndGet();
                    } else {
                        failureCount.incrementAndGet();
                    }
                    
                    log.debug("线程[{}] 扣减结果: {}", threadNo, result);
                } catch (Exception e) {
                    log.error("线程[{}] 异常: {}", threadNo, e.getMessage(), e);
                    failureCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        // 等待所有线程完成
        latch.await();
        executorService.shutdown();

        // 验证结果
        int expectedDeductCount = initialAvailableStock / quantityPerThread;
        log.info("====== 并发测试结果 ======");
        log.info("总线程数: {}", threadCount);
        log.info("成功次数: {}", successCount.get());
        log.info("失败次数: {}", failureCount.get());
        log.info("预期成功次数: {}", expectedDeductCount);

        Stock finalStock = stockService.getStockWithCache(skuId);
        log.info("最终可用库存: {}", finalStock.getAvailableStock());
        log.info("库存版本: {}", finalStock.getVersion());

        // 验证库存永不为负
        assert finalStock.getAvailableStock() >= 0 : "库存不应该为负数";
        
        // 验证扣减数量正确
        int totalDeducted = (initialAvailableStock - finalStock.getAvailableStock());
        int expectedTotal = successCount.get() * quantityPerThread;
        log.info("总扣减数: {}, 预期扣减数: {}", totalDeducted, expectedTotal);
        assert totalDeducted == expectedTotal : "扣减数量应该相等";
    }

    /**
     * 测试库存缓存
     */
    @Test
    void testStockCache() {
        Long skuId = 1003L;

        log.info("====== 测试库存缓存 ======");

        // 第一次查询（缓存未命中，会查数据库）
        long startTime1 = System.currentTimeMillis();
        Stock stock1 = stockService.getStockWithCache(skuId);
        long duration1 = System.currentTimeMillis() - startTime1;
        log.info("第一次查询耗时: {}ms（缓存未命中）", duration1);

        // 第二次查询（缓存命中，会从Redis返回）
        long startTime2 = System.currentTimeMillis();
        Stock stock2 = stockService.getStockWithCache(skuId);
        long duration2 = System.currentTimeMillis() - startTime2;
        log.info("第二次查询耗时: {}ms（缓存命中）", duration2);

        // 验证两次查询结果相同
        assert stock1.getId().equals(stock2.getId()) : "两次查询结果应该相同";
        
        // 验证缓存加速（第二次应该更快）
        log.info("缓存加速效果: 第二次查询比第一次快{}ms", duration1 - duration2);

        // 刷新缓存
        stockService.refreshStockCache(skuId);
        log.info("缓存已刷新");

        // 查询后缓存应该重新加载
        Stock stock3 = stockService.getStockWithCache(skuId);
        assert stock3.getId().equals(stock1.getId()) : "刷新缓存后查询结果应该相同";
    }

    /**
     * 测试幂等性工具
     */
    @Test
    void testIdempotentUtil() {
        String businessId = "IDEMPOTENT_TEST_001";
        String operationType = "TEST_OPERATION";

        log.info("====== 测试幂等性工具 ======");

        // 第一次标记
        boolean marked1 = idempotentUtil.markAsOperated(businessId, operationType);
        log.info("第一次标记: {}", marked1);
        assert marked1 : "第一次标记应该成功";

        // 验证已标记
        boolean isOperated = idempotentUtil.isOperated(businessId, operationType);
        log.info("检查是否已标记: {}", isOperated);
        assert isOperated : "应该检测到已标记";

        // 第二次标记（应该失败，已存在）
        boolean marked2 = idempotentUtil.markAsOperated(businessId, operationType);
        log.info("第二次标记: {}", marked2);
        assert !marked2 : "第二次标记应该失败";

        // 获取标记时间
        Long operatedTime = idempotentUtil.getOperatedTime(businessId, operationType);
        log.info("标记时间: {}", operatedTime);
        assert operatedTime != null : "应该获取到标记时间";

        // 清除标记
        idempotentUtil.clearOperated(businessId, operationType);
        log.info("标记已清除");

        // 验证已清除
        boolean isOperated2 = idempotentUtil.isOperated(businessId, operationType);
        log.info("检查是否仍已标记: {}", isOperated2);
        assert !isOperated2 : "应该检测到标记已清除";
    }

    /**
     * 测试全链路追踪ID
     */
    @Test
    void testTraceId() {
        log.info("====== 测试全链路追踪ID ======");

        // 生成追踪ID
        String traceId1 = TraceIdUtil.generateTraceId();
        log.info("生成的追踪ID: {}", traceId1);

        // 设置追踪ID
        TraceIdUtil.setTraceId(traceId1);
        String retrievedTraceId = TraceIdUtil.getTraceId();
        log.info("获取的追踪ID: {}", retrievedTraceId);
        assert traceId1.equals(retrievedTraceId) : "追踪ID应该相等";

        // 清除追踪ID
        TraceIdUtil.clearTraceId();
        String traceId2 = TraceIdUtil.getTraceId();
        log.info("清除后生成的追踪ID: {}", traceId2);
        assert !traceId1.equals(traceId2) : "清除后应该生成新的追踪ID";
    }

    /**
     * 性能测试
     * 测试不同并发级别下的吞吐量
     */
    @Test
    void testPerformance() throws Exception {
        Long skuId = 1004L;
        int[] concurrencies = {1, 5, 10, 20, 50};
        int requestsPerThread = 100;

        log.info("====== 性能测试 ======");
        log.info("每个线程的请求数: {}", requestsPerThread);

        for (int concurrency : concurrencies) {
            testConcurrency(skuId, concurrency, requestsPerThread);
        }
    }

    private void testConcurrency(Long skuId, int concurrency, int requestsPerThread) throws Exception {
        ExecutorService executorService = Executors.newFixedThreadPool(concurrency);
        CountDownLatch latch = new CountDownLatch(concurrency);
        AtomicInteger totalRequests = new AtomicInteger(0);
        AtomicInteger successCount = new AtomicInteger(0);

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < concurrency; i++) {
            final int threadNo = i;
            executorService.execute(() -> {
                for (int j = 0; j < requestsPerThread; j++) {
                    try {
                        String businessId = "PERF_TEST_" + threadNo + "_" + j;
                        String traceId = TraceIdUtil.generateTraceId();
                        
                        boolean result = stockService.deductStock(
                                skuId,
                                1,
                                businessId,
                                traceId
                        );
                        
                        totalRequests.incrementAndGet();
                        if (result) {
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        totalRequests.incrementAndGet();
                    }
                }
                latch.countDown();
            });
        }

        latch.await();
        long duration = System.currentTimeMillis() - startTime;
        executorService.shutdown();

        double tps = (successCount.get() * 1000.0) / duration;
        log.info("并发数: {}, 成功请求: {}, 总耗时: {}ms, TPS: {:.2f}",
                 concurrency, successCount.get(), duration, tps);
    }
}
