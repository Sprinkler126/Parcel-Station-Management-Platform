-- =============================================================================
-- 演示种子数据（文档 §14）
--
-- 【为什么放在独立的 db/seed 目录】
-- application.yml 的 flyway.locations 同时包含 db/migration 与 db/seed，
-- application-test.yml 只含 db/migration。演示数据因此进不了测试库——
-- 集成测试的断言大量依赖精确计数（total=2、available=99），
-- 一旦种子数据混进来，所有计数断言会莫名其妙地偏移，而且报错信息毫无指向性。
--
-- 【为什么版本号从 900 起跳】
-- 给真实的 schema 演进留出 V2~V899 的空间，避免以后加表时和种子数据抢版本号。
--
-- 【为什么全部用 TIMESTAMPADD 而不是 DATE_ADD】
-- 已实测：H2 2.2.224 的 MySQL 兼容模式不认 DATE_ADD(x, INTERVAL -3 DAY)，
-- 报 Function "date_add" not found [90022-224]；TIMESTAMPADD 在 H2 与 MySQL 上均可用。
-- 用相对时间而非硬编码时间戳，是为了让种子数据在任何一天启动都恰好落在
-- 48h / 72h / 冷却期的两侧——写死 '2026-03-01 10:00:00' 的话，
-- 演示数据过两天就全变成"超期 30 天"，滞留分级三色全糊成一片红。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 货架排：覆盖 NORMAL / TIGHT / EMERGENCY 三档
-- -----------------------------------------------------------------------------
insert into code_space (prefix, capacity, cursor_pos, cooldown_mode, cooldown_days,
                        tier, enabled, updated_at) values
    ('15-1', 9999, 12, 'AUTO',   7,  'NORMAL',    1, CURRENT_TIMESTAMP),
    ('15-2', 9999,  4, 'AUTO',   7,  'NORMAL',    1, CURRENT_TIMESTAMP),
    -- 小容量排，用来演示自适应：200 容量下塞满即进 TIGHT
    ('16-1',  200, 30, 'AUTO',   5,  'NORMAL',    1, CURRENT_TIMESTAMP),
    -- 更小的一排，演示 EMERGENCY 强制复用
    ('16-2',  100, 88, 'AUTO',   3,  'EMERGENCY', 1, CURRENT_TIMESTAMP),
    -- 手动锁定冷却期的一排，演示 MANUAL 模式与 P3001 安全校验
    ('17-1',  500, 10, 'MANUAL', 14, 'NORMAL',    1, CURRENT_TIMESTAMP);

-- -----------------------------------------------------------------------------
-- 在库包裹：故意骑在 48h 与 72h 两条滞留线的两侧
--
-- 47h59m 与 48h01m 这一对是刻意安排的：F4 的档位判据是 >= 48h，
-- 演示时把这两条并排放在列表里，能一眼看出边界取的是闭区间还是开区间。
-- 只造 10h 和 100h 的数据，边界写反了也测不出来。
-- -----------------------------------------------------------------------------
insert into parcel (tracking_no, courier, contact_no, contact_type, real_suffix, suffix_source,
                    receiver_name, pickup_code, code_prefix, code_seq, code_source,
                    code_slot_flag, code_reuse_forced, status, active_flag,
                    inbound_at, outbound_at, urge_count, remark, operator,
                    created_at, updated_at) values

-- ① NORMAL 档：刚入库 2 小时，真实号（REAL）
('SF1234567890123', 'SF', '13812345678', 'REAL', '5678', 'DERIVED', '张伟',
 '15-1-1', '15-1', 1, 'AUTO', 1, 0, 'PENDING', 1,
 TIMESTAMPADD(HOUR, -2, CURRENT_TIMESTAMP), NULL, 0, NULL, '站员A',
 TIMESTAMPADD(HOUR, -2, CURRENT_TIMESTAMP), TIMESTAMPADD(HOUR, -2, CURRENT_TIMESTAMP)),

-- ② NORMAL 档边界内侧：47h59m，仍不该标滞留
('JD0000000000456', 'JD', '138****5678', 'MASKED', '5678', 'MASK', '张伟',
 '15-1-2', '15-1', 2, 'AUTO', 1, 0, 'PENDING', 1,
 TIMESTAMPADD(MINUTE, -2879, CURRENT_TIMESTAMP), NULL, 0, NULL, '站员A',
 TIMESTAMPADD(MINUTE, -2879, CURRENT_TIMESTAMP), TIMESTAMPADD(MINUTE, -2879, CURRENT_TIMESTAMP)),

-- ③ WARN 档边界外侧：48h01m，应转橙色
('YT7788990011223', 'YT', '13900001111', 'REAL', '1111', 'DERIVED', '李娜',
 '15-1-3', '15-1', 3, 'AUTO', 1, 0, 'PENDING', 1,
 TIMESTAMPADD(MINUTE, -2881, CURRENT_TIMESTAMP), NULL, 1,
 '电话已通知一次', '站员B',
 TIMESTAMPADD(MINUTE, -2881, CURRENT_TIMESTAMP), TIMESTAMPADD(HOUR, -6, CURRENT_TIMESTAMP)),

-- ④ WARN 档中段：60h
('ZT1122334455667', 'ZT', '13700002222', 'REAL', '2222', 'DERIVED', '王强',
 '15-1-4', '15-1', 4, 'AUTO', 1, 0, 'PENDING', 1,
 TIMESTAMPADD(HOUR, -60, CURRENT_TIMESTAMP), NULL, 0, NULL, '站员A',
 TIMESTAMPADD(HOUR, -60, CURRENT_TIMESTAMP), TIMESTAMPADD(HOUR, -60, CURRENT_TIMESTAMP)),

-- ⑤ ALERT 档边界外侧：73h，应转红色
('STO123456789012', 'STO', '13600003333', 'REAL', '3333', 'DERIVED', '刘洋',
 '15-1-5', '15-1', 5, 'AUTO', 1, 0, 'PENDING', 1,
 TIMESTAMPADD(HOUR, -73, CURRENT_TIMESTAMP), NULL, 2,
 '两次催取未响应', '站员B',
 TIMESTAMPADD(HOUR, -73, CURRENT_TIMESTAMP), TIMESTAMPADD(HOUR, -3, CURRENT_TIMESTAMP)),

-- ⑥ ALERT 档深水区：9 天，演示长期滞留件
('YD9988776655443', 'YD', '13500004444', 'REAL', '4444', 'DERIVED', '陈静',
 '15-1-6', '15-1', 6, 'AUTO', 1, 0, 'PENDING', 1,
 TIMESTAMPADD(DAY, -9, CURRENT_TIMESTAMP), NULL, 3,
 '长期滞留，待退回', '站员A',
 TIMESTAMPADD(DAY, -9, CURRENT_TIMESTAMP), TIMESTAMPADD(DAY, -1, CURRENT_TIMESTAMP)),

-- ⑦ AXB 虚拟号，real_suffix 为空：演示"入库时拿不到尾号，取件时补录"的链路。
--    这一条是隐私面单场景的核心样本——没有它，尾号补录功能在演示中无从触发。
('SF5544332211009', 'SF', '17012345001', 'VIRTUAL', NULL, NULL, '收件人',
 '15-1-7', '15-1', 7, 'AUTO', 1, 0, 'PENDING', 1,
 TIMESTAMPADD(HOUR, -20, CURRENT_TIMESTAMP), NULL, 0,
 'AXB 虚拟号，尾号待补录', '站员A',
 TIMESTAMPADD(HOUR, -20, CURRENT_TIMESTAMP), TIMESTAMPADD(HOUR, -20, CURRENT_TIMESTAMP)),

-- ⑧⑨ 同一尾号 5678 的另外两件（分布在 15-2）：演示按尾号批量取件的聚合
('SF1111222233334', 'SF', '13812345678', 'REAL', '5678', 'DERIVED', '张伟',
 '15-2-1', '15-2', 1, 'AUTO', 1, 0, 'PENDING', 1,
 TIMESTAMPADD(HOUR, -30, CURRENT_TIMESTAMP), NULL, 0, NULL, '站员C',
 TIMESTAMPADD(HOUR, -30, CURRENT_TIMESTAMP), TIMESTAMPADD(HOUR, -30, CURRENT_TIMESTAMP)),
('JD5555666677778', 'JD', '13812345678', 'REAL', '5678', 'DERIVED', '张伟',
 '15-2-2', '15-2', 2, 'AUTO', 1, 0, 'PENDING', 1,
 TIMESTAMPADD(HOUR, -50, CURRENT_TIMESTAMP), NULL, 0, NULL, '站员C',
 TIMESTAMPADD(HOUR, -50, CURRENT_TIMESTAMP), TIMESTAMPADD(HOUR, -50, CURRENT_TIMESTAMP)),

-- -----------------------------------------------------------------------------
-- 已出库但码仍在冷却期内：active_flag=NULL 而 code_slot_flag=1
-- 这是 INV-1 两条生命线的直观样本——架上已空，码却仍不可用
-- -----------------------------------------------------------------------------

-- ⑩ 昨天取走，7 天冷却期还剩 6 天
('SF8888888888881', 'SF', '13411112222', 'REAL', '2222', 'DERIVED', '赵敏',
 '15-1-8', '15-1', 8, 'AUTO', 1, 0, 'PICKED_UP', NULL,
 TIMESTAMPADD(DAY, -3, CURRENT_TIMESTAMP), TIMESTAMPADD(DAY, -1, CURRENT_TIMESTAMP), 0,
 NULL, '站员A',
 TIMESTAMPADD(DAY, -3, CURRENT_TIMESTAMP), TIMESTAMPADD(DAY, -1, CURRENT_TIMESTAMP)),

-- ⑪ 冷却期只剩 1 小时：演示"即将回炉"的临界样本
('SF8888888888882', 'SF', '13422223333', 'REAL', '3333', 'DERIVED', '孙磊',
 '15-1-9', '15-1', 9, 'AUTO', 1, 0, 'PICKED_UP', NULL,
 TIMESTAMPADD(DAY, -9, CURRENT_TIMESTAMP), TIMESTAMPADD(HOUR, -167, CURRENT_TIMESTAMP), 0,
 NULL, '站员B',
 TIMESTAMPADD(DAY, -9, CURRENT_TIMESTAMP), TIMESTAMPADD(HOUR, -167, CURRENT_TIMESTAMP)),

-- ⑫ 冷却期刚满 1 小时但回炉任务尚未跑到：code_slot_flag 仍是 1。
--    这一条专门用来演示"分配路径按需自愈"——下次分配命中该号时会在同一事务内定向释放，
--    不必等回炉任务。少了它，双保险机制在演示中完全看不出来。
('SF8888888888883', 'SF', '13433334444', 'REAL', '4444', 'DERIVED', '周涛',
 '15-1-10', '15-1', 10, 'AUTO', 1, 0, 'PICKED_UP', NULL,
 TIMESTAMPADD(DAY, -10, CURRENT_TIMESTAMP), TIMESTAMPADD(HOUR, -169, CURRENT_TIMESTAMP), 0,
 NULL, '站员B',
 TIMESTAMPADD(DAY, -10, CURRENT_TIMESTAMP), TIMESTAMPADD(HOUR, -169, CURRENT_TIMESTAMP)),

-- ⑬ 已回炉：code_slot_flag=NULL，该码可被复用
('SF8888888888884', 'SF', '13444445555', 'REAL', '5555', 'DERIVED', '吴敏',
 '15-1-11', '15-1', 11, 'AUTO', NULL, 0, 'PICKED_UP', NULL,
 TIMESTAMPADD(DAY, -20, CURRENT_TIMESTAMP), TIMESTAMPADD(DAY, -15, CURRENT_TIMESTAMP), 0,
 NULL, '站员A',
 TIMESTAMPADD(DAY, -20, CURRENT_TIMESTAMP), TIMESTAMPADD(DAY, -8, CURRENT_TIMESTAMP)),

-- ⑭ 退回件：RETURNED 也是终态，码同样进冷却（实施阶段与产品确认的 A2 结论）
('YT0000111122223', 'YT', '13455556666', 'REAL', '6666', 'DERIVED', '郑爽',
 '15-1-12', '15-1', 12, 'AUTO', 1, 0, 'RETURNED', NULL,
 TIMESTAMPADD(DAY, -12, CURRENT_TIMESTAMP), TIMESTAMPADD(DAY, -2, CURRENT_TIMESTAMP), 3,
 '超期未取，已退回快递公司', '站员B',
 TIMESTAMPADD(DAY, -12, CURRENT_TIMESTAMP), TIMESTAMPADD(DAY, -2, CURRENT_TIMESTAMP)),

-- ⑮ 同一运单号的历史件 + 现存件：演示 uk_tracking_active 允许多条终态记录。
--    先取走一次（终态，active_flag=NULL），再次入库（active_flag=1），两行共存。
('SF7777000011112', 'SF', '13466667777', 'REAL', '7777', 'DERIVED', '何洁',
 '15-2-3', '15-2', 3, 'AUTO', 1, 0, 'PICKED_UP', NULL,
 TIMESTAMPADD(DAY, -5, CURRENT_TIMESTAMP), TIMESTAMPADD(DAY, -4, CURRENT_TIMESTAMP), 0,
 '第一次取件后被拒收退回站点', '站员A',
 TIMESTAMPADD(DAY, -5, CURRENT_TIMESTAMP), TIMESTAMPADD(DAY, -4, CURRENT_TIMESTAMP)),
('SF7777000011112', 'SF', '13466667777', 'REAL', '7777', 'DERIVED', '何洁',
 '15-2-4', '15-2', 4, 'AUTO', 1, 0, 'PENDING', 1,
 TIMESTAMPADD(DAY, -1, CURRENT_TIMESTAMP), NULL, 0,
 '重投，同运单号第二次入库', '站员A',
 TIMESTAMPADD(DAY, -1, CURRENT_TIMESTAMP), TIMESTAMPADD(DAY, -1, CURRENT_TIMESTAMP)),

-- ⑯ 手动指定码的样本（MANUAL），落在 17-1
('SF6666555544443', 'SF', '13477778888', 'REAL', '8888', 'DERIVED', '林峰',
 '17-1-10', '17-1', 10, 'MANUAL', 1, 0, 'PENDING', 1,
 TIMESTAMPADD(HOUR, -8, CURRENT_TIMESTAMP), NULL, 0,
 '大件，站员指定靠门货位', '站员C',
 TIMESTAMPADD(HOUR, -8, CURRENT_TIMESTAMP), TIMESTAMPADD(HOUR, -8, CURRENT_TIMESTAMP)),

-- ⑰ EMERGENCY 强制复用的样本：code_reuse_forced=1，落在 16-2
('SF3333222211110', 'SF', '13488889999', 'REAL', '9999', 'DERIVED', '徐磊',
 '16-2-88', '16-2', 88, 'AUTO', 1, 1, 'PENDING', 1,
 TIMESTAMPADD(HOUR, -4, CURRENT_TIMESTAMP), NULL, 0,
 '码空间告急，提前复用了 3 天前出库的码', '站员A',
 TIMESTAMPADD(HOUR, -4, CURRENT_TIMESTAMP), TIMESTAMPADD(HOUR, -4, CURRENT_TIMESTAMP));

-- -----------------------------------------------------------------------------
-- 流水：只给关键样本铺，够演示"取件后能查到完整轨迹"即可。
-- INV-6 要求所有状态变更都写流水，但种子数据是"假装历史已发生"，
-- 逐条补齐三十行流水对演示没有增量价值，反而让脚本难以维护。
-- -----------------------------------------------------------------------------
insert into parcel_event (parcel_id, event_type, from_status, to_status,
                          operator, detail, occurred_at)
select p.id, 'INBOUND', NULL, 'PENDING', p.operator,
       concat('取件码 ', p.pickup_code, '，来源 ', p.code_source), p.inbound_at
from parcel p;

insert into parcel_event (parcel_id, event_type, from_status, to_status,
                          operator, detail, occurred_at)
select p.id, 'PICKUP', 'PENDING', 'PICKED_UP', p.operator,
       concat('取件码 ', p.pickup_code, ' 已取件'), p.outbound_at
from parcel p where p.status = 'PICKED_UP';

insert into parcel_event (parcel_id, event_type, from_status, to_status,
                          operator, detail, occurred_at)
select p.id, 'RETURN', 'PENDING', 'RETURNED', p.operator,
       concat('取件码 ', p.pickup_code, ' 已退回：', coalesce(p.remark, '')), p.outbound_at
from parcel p where p.status = 'RETURNED';

insert into parcel_event (parcel_id, event_type, from_status, to_status,
                          operator, detail, occurred_at)
select p.id, 'SLOT_RELEASE', NULL, NULL, 'system',
       concat('取件码 ', p.pickup_code, ' 冷却期满，槽位回炉可复用'),
       TIMESTAMPADD(DAY, 7, p.outbound_at)
from parcel p where p.code_slot_flag is null and p.outbound_at is not null;

insert into parcel_event (parcel_id, event_type, from_status, to_status,
                          operator, detail, occurred_at)
select p.id, 'URGE', NULL, NULL, p.operator,
       concat('第 ', p.urge_count, ' 次催取'), p.updated_at
from parcel p where p.urge_count > 0;

insert into parcel_event (parcel_id, event_type, from_status, to_status,
                          operator, detail, occurred_at)
select p.id, 'SLOT_FORCE_REUSE', NULL, NULL, p.operator,
       concat('码 ', p.pickup_code, ' 在 EMERGENCY 档下被提前复用'), p.inbound_at
from parcel p where p.code_reuse_forced = 1;
