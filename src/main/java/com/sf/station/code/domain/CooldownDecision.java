package com.sf.station.code.domain;

/**
 * 冷却期决策结果。
 *
 * @param newDays 决策后的冷却天数
 * @param tier    档位
 * @param changed 是否发生变更（滞回带内为 false）
 * @param reason  可解释性：站员看到 15-1 是 4 天而 15-2 是 30 天，必须能查到为什么
 */
public record CooldownDecision(int newDays, Tier tier, boolean changed, String reason) {
}
