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
    order_id VARCHAR(64) NOT NULL,
    sku_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    amount DECIMAL(12, 2),
    status SMALLINT NOT NULL DEFAULT 1,
    trace_id VARCHAR(64),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_order_id UNIQUE (order_id)
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
CREATE INDEX idx_sku_id_order ON "order"(sku_id);
CREATE INDEX idx_order_status ON "order"(status);
CREATE INDEX idx_trace_id_order ON "order"(trace_id);

-- =====================================================================
-- 库存记录表 (stock)
-- =====================================================================
CREATE TABLE IF NOT EXISTS stock (
    id BIGSERIAL PRIMARY KEY,
    sku_id BIGINT NOT NULL,
    total_stock INT NOT NULL DEFAULT 0,
    available_stock INT NOT NULL DEFAULT 0,
    locked_stock INT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sku_id UNIQUE (sku_id),
    CHECK (available_stock >= 0),
    CHECK (locked_stock >= 0),
    CHECK (total_stock >= 0),
    CHECK (available_stock + locked_stock <= total_stock)
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

-- =====================================================================
-- 库存扣减记录表 (stock_change_record)
-- =====================================================================
CREATE TABLE IF NOT EXISTS stock_change_record (
    id              BIGSERIAL PRIMARY KEY,

    -- 幂等与业务关联（核心防重字段）
    idempotent_id   VARCHAR(64),                    -- 全局幂等键（支付回调、MQ消费、定时任务等场景使用）
    business_id     VARCHAR(64) NOT NULL,           -- 订单号、退款单号、取消单号、补偿批次号等

    sku_id          BIGINT NOT NULL,

    -- 操作类型（建议扩展到 6~8 种，覆盖更多场景）
    operation_type  SMALLINT NOT NULL,              -- 1=预扣减, 2=正式扣减, 3=库存释放,
                                                    -- 4=补偿增加, 5=补偿扣减, 6=手动调整, 7=退货入库, 8=其他

    quantity        INT NOT NULL CHECK (quantity >= 0),                   -- 操作数量

    -- 库存快照（审计与对账核心）
    available_before  INT NOT NULL,
    available_after   INT NOT NULL,
    locked_before     INT NOT NULL DEFAULT 0,
    locked_after      INT NOT NULL DEFAULT 0,
    total_before      INT NOT NULL DEFAULT 0,       -- 可选，如果业务关心总库存
    total_after       INT NOT NULL DEFAULT 0,

    -- 状态与结果
    status          SMALLINT NOT NULL DEFAULT 0,    -- 0=处理中, 1=成功, 2=失败, 3=已补偿, 4=已回滚
    error_message   TEXT,

    -- 可观测性与追踪
    trace_id        VARCHAR(64) NOT NULL,
    operator        VARCHAR(64),                    -- 操作人/系统（可选：user_id / system / cron / mq-consumer 等）
    remark          VARCHAR(255),

    -- 补偿相关（当 operation_type 为补偿类时有意义）
    compensation_reason  VARCHAR(64),               -- PAYMENT_TIMEOUT, ORDER_CANCELLED, RETURNED, SYSTEM_BUG, MANUAL 等

    -- 时间
    create_time     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,  -- 仅在极少数需要更新的场景使用（如人工补偿后改状态）

    -- 唯一约束（根据实际幂等粒度选择 1~2 个）
    CONSTRAINT uk_idempotent UNIQUE (idempotent_id) DEFERRABLE INITIALLY DEFERRED,  -- 允许部分记录 idempotent_id 为空
    CONSTRAINT uk_business_type UNIQUE (business_id, operation_type) DEFERRABLE INITIALLY DEFERRED  -- 防同一个业务多次同类型操作
);

-- 注释（强烈建议都加上，便于新人理解）
COMMENT ON TABLE stock_change_record IS '库存变更记录表（合并幂等 + 审计日志），记录每一次库存操作的完整上下文，用于防重、审计、对账、补偿、问题定位';
COMMENT ON COLUMN stock_change_record.idempotent_id          IS '幂等键（可为空，部分场景如手动调整无需幂等）';
COMMENT ON COLUMN stock_change_record.business_id            IS '业务单据号（订单、退款单、补偿批次等）';
COMMENT ON COLUMN stock_change_record.operation_type         IS '操作类型：1=预扣减 2=正式扣减 3=释放 4=补偿增加 5=补偿扣减 6=手动调整 7=退货入库';
COMMENT ON COLUMN stock_change_record.quantity               IS '变更数量';
COMMENT ON COLUMN stock_change_record.available_before       IS '操作前可用库存快照';
COMMENT ON COLUMN stock_change_record.available_after        IS '操作后可用库存快照';
COMMENT ON COLUMN stock_change_record.status                 IS '0=处理中 1=成功 2=失败 3=已补偿 4=已回滚';
COMMENT ON COLUMN stock_change_record.compensation_reason    IS '补偿原因，仅补偿类操作有值';
COMMENT ON COLUMN stock_change_record.trace_id               IS '全链路追踪ID';

-- =====================================================================
-- 消息投递记录表 (message_delivery)
-- =====================================================================
CREATE TABLE IF NOT EXISTS message_delivery (
    id BIGSERIAL PRIMARY KEY,
    message_id VARCHAR(64) NOT NULL,
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
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_message_id UNIQUE (message_id)
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
CREATE INDEX idx_message_delivery_status ON message_delivery(status);
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
