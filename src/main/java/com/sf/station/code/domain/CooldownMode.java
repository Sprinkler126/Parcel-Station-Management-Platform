package com.sf.station.code.domain;

/** 冷却期设定模式。 */
public enum CooldownMode {
    /** 由 CooldownPolicyApplier 每日自适应调节 */
    AUTO,
    /** 管理员手动指定固定值，但仍须过安全校验（P3001） */
    MANUAL
}
