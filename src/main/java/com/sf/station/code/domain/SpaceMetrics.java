package com.sf.station.code.domain;

/**
 * 一排的指标快照（文档 §9.4）。
 *
 * <p>槽位占用量 = 在库量 + 冷却量。一排日周转 50 件、冷却 7 天则常态占用约 400 个码，
 * 占 9999 空间的 4%，空间压力可忽略——这个测算是自适应机制的前提。
 *
 * @param inStock      在库量（PENDING）
 * @param cooling      冷却量（已出库但冷却未满）
 * @param dailyInbound 近 14 天日入库量 EWMA
 * @param dailyPickup  近 14 天日出库量 EWMA
 */
public record SpaceMetrics(String prefix, int capacity, int inStock, int cooling,
                           double dailyInbound, double dailyPickup) {

    public int available() {
        return capacity - inStock - cooling;
    }

    public double availableRatio() {
        return capacity == 0 ? 0 : (double) available() / capacity;
    }
}
