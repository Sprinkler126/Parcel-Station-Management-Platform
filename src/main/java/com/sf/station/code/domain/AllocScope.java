package com.sf.station.code.domain;

/** AUTO 模式的取码范围（文档 §2 F5）。 */
public enum AllocScope {
    /** 锁定到排，扫码连续入库默认 */
    ROW,
    /** 在该货架各排中挑占用率最低的一排 */
    SHELF,
    /** 在全部启用排中选择 */
    FULL
}
