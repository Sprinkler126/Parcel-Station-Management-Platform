package com.sf.station.parcel.domain;

/**
 * 事件类型（INV-6：所有状态变更写 parcel_event 且不覆盖历史）。
 *
 * <p>撤销不是"改回去"，而是追加一条反向事件 CANCEL_PICKUP，流水中完整保留
 * "曾于某时出库、某时撤销、操作人是谁"。
 */
public enum EventType {
    /** 入库 */
    INBOUND,
    /** 确认取件 */
    PICKUP,
    /** 撤销取件 */
    CANCEL_PICKUP,
    /** 拒收退回 */
    RETURN,
    /** 催取 */
    URGE,
    /** 补录真实尾号 */
    SUFFIX_PATCH,
    /** 码槽位回炉释放 */
    SLOT_RELEASE,
    /** EMERGENCY 档强制提前复用 */
    SLOT_FORCE_REUSE,
    /** 异常件标记 */
    REMARK
}
