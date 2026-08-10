package com.sf.station.code.domain;

/**
 * 冷却自适应参数（文档 §9.1，已定稿）。
 *
 * @param minDays            下限 3，TIGHT / EMERGENCY 档取此值
 * @param maxDays            上限 90
 * @param bufferDays         预留 3 天入库量
 * @param defaultDays        新建排的初始值 7
 * @param tightThreshold     0.30，可用率低于此进入 TIGHT
 * @param emergencyThreshold 0.10，可用率低于此进入 EMERGENCY
 * @param ewmaAlpha          0.3，近 14 天日计数平滑系数
 */
public record CooldownConfig(int minDays, int maxDays, int bufferDays, int defaultDays,
                             double tightThreshold, double emergencyThreshold, double ewmaAlpha) {

    /** 文档定稿的默认参数 */
    public static CooldownConfig defaults() {
        return new CooldownConfig(3, 90, 3, 7, 0.30, 0.10, 0.3);
    }
}
