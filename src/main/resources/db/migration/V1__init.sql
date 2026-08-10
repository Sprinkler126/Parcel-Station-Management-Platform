-- =============================================================================
-- 末端驿站包裹管理系统 · 初始 schema
-- H2 以 MySQL 兼容模式启动，本脚本在 H2 与 MySQL 上通用。
-- =============================================================================

create table parcel (
    id                  bigint auto_increment primary key,
    tracking_no         varchar(32)  not null,
    courier             varchar(16)  not null,

    -- 联系方式：隐私面单下手机号双职责拆分
    contact_no          varchar(24)  not null,
    contact_type        varchar(8)   not null,   -- REAL / MASKED / VIRTUAL
    real_suffix         varchar(4),              -- 可空：AXB 单入库时未知，支持后续补录
    suffix_source       varchar(12),             -- DERIVED / MASK / PLATFORM / MANUAL
    receiver_name       varchar(32),

    -- 取件码
    pickup_code         varchar(24)  not null,
    code_prefix         varchar(16)  not null,
    code_seq            int          not null,
    code_source         varchar(12)  not null,   -- AUTO / MANUAL
    code_slot_flag      tinyint,                 -- 1=占用或冷却中, NULL=已回炉
    code_reuse_forced   tinyint      default 0,  -- 1=EMERGENCY 提前复用

    -- 状态
    status              varchar(16)  not null,   -- PENDING / PICKED_UP / RETURNED
    active_flag         tinyint,                 -- 1=未完结, NULL=终态

    inbound_at          datetime(3)  not null,
    outbound_at         datetime(3),
    urge_count          int          default 0,
    last_urged_at       datetime(3),
    remark              varchar(255),
    operator            varchar(32),
    created_at          datetime(3)  not null,
    updated_at          datetime(3)  not null
);

-- INV-1 + INV-2：利用唯一索引允许多个 NULL 的特性实现两条相互独立的唯一性约束。
-- uk_tracking_active：同一运单号只允许一条未完结记录（active_flag=1），
--                     但允许任意多条终态记录（active_flag=NULL），
--                     从而支持拒收重投、取错退回等合法的二次入库场景。
-- uk_code_slot      ：同一个取件码同一时刻只允许一个持有者（code_slot_flag=1），
--                     回炉后置 NULL 释放，该码方可被复用。
create unique index uk_tracking_active on parcel (tracking_no, active_flag);
create unique index uk_code_slot       on parcel (pickup_code, code_slot_flag);

create index idx_alloc     on parcel (code_prefix, code_slot_flag, code_seq); -- 位图加载，覆盖索引
create index idx_suffix    on parcel (real_suffix, status);                   -- 主检索路径
create index idx_pickup    on parcel (pickup_code);
create index idx_inbound   on parcel (inbound_at);
create index idx_outbound  on parcel (code_prefix, outbound_at);              -- 回炉与强制复用

-- INV-6：状态流水，只追加不覆盖
create table parcel_event (
    id           bigint auto_increment primary key,
    parcel_id    bigint      not null,
    event_type   varchar(24) not null,  -- INBOUND/PICKUP/CANCEL_PICKUP/RETURN/URGE/
                                        -- SUFFIX_PATCH/SLOT_RELEASE/SLOT_FORCE_REUSE/REMARK
    from_status  varchar(16),
    to_status    varchar(16),
    operator     varchar(32),
    detail       varchar(512),
    occurred_at  datetime(3) not null
);
create index idx_event_parcel on parcel_event (parcel_id, occurred_at);

-- 按排的码空间配置
create table code_space (
    prefix           varchar(16) primary key,
    capacity         int         not null default 9999,
    cursor_pos       int         not null default 0,
    cooldown_mode    varchar(8)  not null default 'AUTO',   -- AUTO / MANUAL
    cooldown_days    int         not null default 7,
    tier             varchar(12) not null default 'NORMAL',
    enabled          tinyint     not null default 1,
    updated_at       datetime(3) not null
);

-- 冷却决策日志，含完整指标快照，支撑可解释性
create table cooldown_policy_log (
    id             bigint auto_increment primary key,
    prefix         varchar(16) not null,
    old_days       int,
    new_days       int,
    tier           varchar(12),
    capacity       int,
    in_stock       int,
    cooling        int,
    available      int,
    daily_inbound  decimal(10,2),
    daily_pickup   decimal(10,2),
    reason         varchar(255),
    decided_at     datetime(3) not null
);
create index idx_policy_log_prefix on cooldown_policy_log (prefix, decided_at);
