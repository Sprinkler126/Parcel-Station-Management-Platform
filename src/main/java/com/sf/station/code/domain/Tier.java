package com.sf.station.code.domain;

/**
 * 码空间紧张档位（文档 §9.3）。
 *
 * <table>
 *   <tr><th>档位</th><th>触发条件 available/capacity</th><th>行为</th></tr>
 *   <tr><td>NORMAL</td><td>&gt; 30%</td><td>按闭式解计算，经滞回后写入</td></tr>
 *   <tr><td>TIGHT</td><td>10% ~ 30%</td><td>强制压到 minDays，看板告警</td></tr>
 *   <tr><td>EMERGENCY</td><td>&lt; 10%，或分子 ≤ 0，或分配失败</td><td>压到 minDays，且允许提前复用</td></tr>
 * </table>
 */
public enum Tier {
    NORMAL,
    TIGHT,
    EMERGENCY
}
