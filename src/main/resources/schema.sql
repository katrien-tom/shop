-- =====================================================================
-- 数据库脚本说明
-- =====================================================================
-- 创建时间：2026-01-30
-- 脚本版本：1.0.0
-- 数据库类型：PostgreSQL
-- 功能说明：库存管理系统相关表的创建及初始化

-- =====================================================================
-- 订单表 (order)
-- =====================================================================
CREATE TABLE IF NOT EXISTS "order" (
    id BIGSERIAL PRIMARY KEY,
    order_id VARCHAR(64) NOT NULL UNIQUE,
    sku_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    amount DECIMAL(12, 2),
    status SMALLINT NOT NULL DEFAULT 1,
    trace_id VARCHAR(64),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 订单表注释
COMMENT ON TABLE "order" IS '订单表';
COMMENT ON COLUMN "order".id IS '主键';
COMMENT ON COLUMN "order".order_id IS '订单ID（业务唯一标识）';
COMMENT ON COLUMN "order".sku_id IS 'SKU ID';
COMMENT ON COLUMN "order".quantity IS '购买数量';
COMMENT ON COLUMN "order".amount IS '订单金额';
COMMENT ON COLUMN "order".status IS '订单状态：1=PENDING(待处理) 2=STOCK_RESERVED(库存预占成功) 3=STOCK_DEDUCTED(库存正式扣减成功) 4=STOCK_FAILED(库存操作失败) 5=PAID(已支付) 6=PAYMENT_FAILED(支付失败) 7=SHIPPED(已发货) 8=DELIVERED(已签收) 9=RETURNED(已退货) 10=CANCELLED(已取消)';
COMMENT ON COLUMN "order".trace_id IS '追踪ID（用于分布式链路追踪）';
COMMENT ON COLUMN "order".create_time IS '创建时间';
COMMENT ON COLUMN "order".update_time IS '更新时间';

-- 订单表索引
CREATE INDEX idx_order_id ON "order"(order_id);
CREATE INDEX idx_sku_id_order ON "order"(sku_id);
CREATE INDEX idx_status ON "order"(status);
CREATE INDEX idx_trace_id_order ON "order"(trace_id);

-- =====================================================================
-- 库存记录表 (stock)
-- =====================================================================
CREATE TABLE IF NOT EXISTS stock (
    id BIGSERIAL PRIMARY KEY,
    sku_id BIGINT NOT NULL UNIQUE,
    total_stock INT NOT NULL DEFAULT 0,
    available_stock INT NOT NULL DEFAULT 0,
    locked_stock INT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sku_id UNIQUE (sku_id)
);

-- 库存表注释
COMMENT ON TABLE stock IS '库存记录表';
COMMENT ON COLUMN stock.id IS '主键';
COMMENT ON COLUMN stock.sku_id IS 'skuID';
COMMENT ON COLUMN stock.total_stock IS '总库存';
COMMENT ON COLUMN stock.available_stock IS '可用库存';
COMMENT ON COLUMN stock.locked_stock IS '已预占库存（下单未支付）';
COMMENT ON COLUMN stock.version IS '乐观锁版本号';
COMMENT ON COLUMN stock.create_time IS '创建时间';
COMMENT ON COLUMN stock.update_time IS '更新时间';

-- 库存表索引
CREATE INDEX idx_stock_sku_id ON stock(sku_id);

-- =====================================================================
-- 库存扣减记录表 (stock_operation)
-- =====================================================================
CREATE TABLE IF NOT EXISTS stock_operation (
    id BIGSERIAL PRIMARY KEY,
    idempotent_id VARCHAR(64) NOT NULL UNIQUE,
    sku_id BIGINT NOT NULL,
    operate_type SMALLINT NOT NULL,
    operate_status SMALLINT NOT NULL DEFAULT 0,
    operate_num INT NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    remark VARCHAR(255),
    CONSTRAINT uk_idempotent_id UNIQUE (idempotent_id)
);

-- 库存扣减记录表注释
COMMENT ON TABLE stock_operation IS '库存扣减记录表';
COMMENT ON COLUMN stock_operation.id IS '主键';
COMMENT ON COLUMN stock_operation.idempotent_id IS '全局唯一幂等ID';
COMMENT ON COLUMN stock_operation.sku_id IS 'skuID';
COMMENT ON COLUMN stock_operation.operate_type IS '操作类型：1=PRE_DEDUCT(预扣减) 2=FORMAL_DEDUCT(正式扣减) 3=STOCK_RELEASE(库存释放)';
COMMENT ON COLUMN stock_operation.operate_status IS '操作状态：0=PROCESSING(处理中) 1=SUCCESS(成功) 2=FAILED(失败)';
COMMENT ON COLUMN stock_operation.operate_num IS '操作数量';
COMMENT ON COLUMN stock_operation.create_time IS '创建时间';
COMMENT ON COLUMN stock_operation.update_time IS '更新时间';
COMMENT ON COLUMN stock_operation.remark IS '备注';

-- 库存扣减记录表索引
CREATE INDEX idx_sku_id ON stock_operation(sku_id);

-- =====================================================================
-- 库存操作日志表 (stock_operation_log)
-- =====================================================================
CREATE TABLE IF NOT EXISTS stock_operation_log (
    id BIGSERIAL PRIMARY KEY,
    business_id VARCHAR(64) NOT NULL,
    sku_id BIGINT NOT NULL,
    operation_type SMALLINT NOT NULL,
    quantity INT NOT NULL,
    stock_before INT NOT NULL,
    stock_after INT NOT NULL,
    status SMALLINT NOT NULL DEFAULT 0,
    compensation_reason VARCHAR(64),
    error_message TEXT,
    trace_id VARCHAR(64) NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 库存操作日志表注释
COMMENT ON TABLE stock_operation_log IS '库存操作日志表，记录所有库存操作（扣减、补偿等），用于审计和幂等性验证';
COMMENT ON COLUMN stock_operation_log.id IS '主键';
COMMENT ON COLUMN stock_operation_log.business_id IS '业务ID（订单ID、库存操作ID等）';
COMMENT ON COLUMN stock_operation_log.sku_id IS 'SKU ID';
COMMENT ON COLUMN stock_operation_log.operation_type IS '操作类型：1=DEDUCT(扣减) 2=REFUND(退款) 3=COMPENSATION(补偿)';
COMMENT ON COLUMN stock_operation_log.quantity IS '操作数量';
COMMENT ON COLUMN stock_operation_log.stock_before IS '操作前库存';
COMMENT ON COLUMN stock_operation_log.stock_after IS '操作后库存';
COMMENT ON COLUMN stock_operation_log.status IS '操作状态：0=PENDING(待处理) 1=SUCCESS(成功) 2=FAILED(失败) 3=COMPENSATED(已补偿)';
COMMENT ON COLUMN stock_operation_log.compensation_reason IS '补偿原因（当operation_type=3时有值）：PAYMENT_FAILED(支付失败) ORDER_CANCELLED(取消订单) ORDER_RETURNED(商品退货) SYSTEM_EXCEPTION(系统异常)';
COMMENT ON COLUMN stock_operation_log.error_message IS '错误信息';
COMMENT ON COLUMN stock_operation_log.trace_id IS '追踪ID（全链路追踪）';
COMMENT ON COLUMN stock_operation_log.create_time IS '创建时间';
COMMENT ON COLUMN stock_operation_log.update_time IS '更新时间';

-- 库存操作日志表索引
CREATE INDEX idx_business_id ON stock_operation_log(business_id);
CREATE INDEX idx_sku_id_log ON stock_operation_log(sku_id);
CREATE INDEX idx_trace_id_log ON stock_operation_log(trace_id);
CREATE INDEX idx_create_time ON stock_operation_log(create_time);

-- =====================================================================
-- 消息投递记录表 (message_delivery)
-- =====================================================================
CREATE TABLE IF NOT EXISTS message_delivery (
    id BIGSERIAL PRIMARY KEY,
    message_id VARCHAR(64) NOT NULL UNIQUE,
    message_type SMALLINT NOT NULL,
    message_content TEXT NOT NULL,
    status SMALLINT NOT NULL DEFAULT 0,
    delivery_count INT NOT NULL DEFAULT 0,
    max_retries INT NOT NULL DEFAULT 5,
    next_retry_time TIMESTAMP,
    target_queue VARCHAR(64),
    trace_id VARCHAR(64),
    error_message TEXT,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 消息投递记录表注释
COMMENT ON TABLE message_delivery IS '消息投递记录表，记录所有发送的消息，支持消息追踪、重试、补偿，实现消息不丢失、不重复';
COMMENT ON COLUMN message_delivery.id IS '主键';
COMMENT ON COLUMN message_delivery.message_id IS '消息ID（唯一标识，防重复投递）';
COMMENT ON COLUMN message_delivery.message_type IS '消息类型：1=STOCK_PRE_DEDUCT(库存预扣减) 2=STOCK_FORMAL_DEDUCT(库存正式扣减) 3=STOCK_RELEASE(库存释放) 4=ORDER_PAY(订单支付) 5=ORDER_CANCEL(订单取消)';
COMMENT ON COLUMN message_delivery.message_content IS '消息内容（JSON格式）';
COMMENT ON COLUMN message_delivery.status IS '投递状态：0=PENDING(待投递) 1=SENT(已发送) 2=CONFIRMED(已确认) 3=FAILED(失败)';
COMMENT ON COLUMN message_delivery.delivery_count IS '投递次数';
COMMENT ON COLUMN message_delivery.max_retries IS '最大重试次数';
COMMENT ON COLUMN message_delivery.next_retry_time IS '下一次重试时间';
COMMENT ON COLUMN message_delivery.target_queue IS '目标队列';
COMMENT ON COLUMN message_delivery.trace_id IS '追踪ID';
COMMENT ON COLUMN message_delivery.error_message IS '错误信息';
COMMENT ON COLUMN message_delivery.create_time IS '创建时间';
COMMENT ON COLUMN message_delivery.update_time IS '更新时间';

-- 消息投递记录表索引
CREATE INDEX idx_message_id ON message_delivery(message_id);
CREATE INDEX idx_status ON message_delivery(status);
CREATE INDEX idx_trace_id ON message_delivery(trace_id);
CREATE INDEX idx_next_retry_time ON message_delivery(next_retry_time);

-- =====================================================================
-- 初始化数据（可选）
-- =====================================================================
-- 插入示例库存记录
INSERT INTO stock (sku_id, total_stock, available_stock, locked_stock, version)
VALUES 
    (1001, 1000, 1000, 0, 0),
    (1002, 500, 500, 0, 0),
    (1003, 200, 200, 0, 0)
ON CONFLICT (sku_id) DO NOTHING;
