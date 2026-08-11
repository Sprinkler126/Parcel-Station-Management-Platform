-- findOccupiedSeqs 先按排与占用标记等值定位，再按出库时间判断在库/冷却状态，
-- 最后直接从索引取得 code_seq，避免为 outbound_at 条件逐行回表。
drop index idx_alloc on parcel;
create index idx_alloc on parcel (code_prefix, code_slot_flag, outbound_at, code_seq);
