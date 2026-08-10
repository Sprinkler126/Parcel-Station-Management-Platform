package com.sf.station.parcel.domain;

/** 包裹状态。PENDING 为唯一的未完结状态（activeFlag = 1）。 */
public enum ParcelStatus {
    /** 在库待取 */
    PENDING,
    /** 已取件 */
    PICKED_UP,
    /** 拒收退回 */
    RETURNED;

    public boolean isTerminal() {
        return this != PENDING;
    }
}
