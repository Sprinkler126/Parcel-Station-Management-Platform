package com.sf.station.code.domain;

/**
 * 冷却期自适应策略（文档 §9.4）。
 *
 * <p><b>策略与执行必须分离。</b>{@code decide} 不碰数据库、不碰 Clock，
 * 入参是指标快照、出参是决策结果，因此滞回、非对称响应、clamp、分档切换
 * 全部可用纯单测覆盖，一个用例一行断言，无需造数据也无需 Spring 上下文。
 * 副作用（读指标、写配置、记日志）留在外层 CooldownPolicyApplier。
 *
 * <p><b>模型</b>：稳态下冷却量 ≈ 日出库量 × 冷却天数，要求任何时刻可用数不低于安全水位：
 * <pre>
 * buffer          = ceil(dailyInboundRate × bufferDays)
 * maxCooldownDays = (capacity − inStock − buffer) / dailyPickupRate
 * target          = clamp(floor(maxCooldownDays), minDays, maxDays)
 * </pre>
 * buffer 按"日均入库量 × 3 天"而非固定比例。固定比例对低周转货架严重过度保守——
 * 一排每天只走 5 件时，20% 的比例相当于预留了 400 天的量。
 *
 * <p><b>缩短要快、延长要慢。</b>号不够用会直接阻断入库作业，是生产事故；
 * 冷却期短一点只是提高取错件概率。因此下调允许一次到位，上调每次最多 +1 天逐步爬升。
 */
public final class CooldownPolicy {

    private CooldownPolicy() {
    }

    public static CooldownDecision decide(SpaceMetrics m, int currentDays, CooldownConfig cfg) {
        double ratio = m.availableRatio();

        if (ratio < cfg.emergencyThreshold()) {
            return apply(currentDays, cfg.minDays(), Tier.EMERGENCY, "可用率过低，强制最短冷却并允许提前复用");
        }
        if (ratio < cfg.tightThreshold()) {
            return apply(currentDays, cfg.minDays(), Tier.TIGHT, "可用率偏紧，压缩冷却期至下限");
        }

        int buffer = (int) Math.ceil(m.dailyInbound() * cfg.bufferDays());
        int numerator = m.capacity() - m.inStock() - buffer;
        if (numerator <= 0) {
            return apply(currentDays, cfg.minDays(), Tier.EMERGENCY, "缓冲已耗尽");
        }

        double rate = Math.max(m.dailyPickup(), 1.0);   // 防除零
        int target = clamp((int) Math.floor(numerator / rate), cfg.minDays(), cfg.maxDays());

        // 快下调：一次到位
        if (target < currentDays) {
            return new CooldownDecision(target, Tier.NORMAL, true,
                    "容量收紧，下调至 " + target + " 天");
        }

        // 滞回带防抖：仅当增幅足够大才上调。滞回只在 NORMAL 档生效，不得阻塞下调
        int hysteresis = Math.max(2, (int) (currentDays * 0.2));
        if (target - currentDays < hysteresis) {
            return new CooldownDecision(currentDays, Tier.NORMAL, false,
                    "位于滞回带内（目标 " + target + " 天，需增幅 ≥ " + hysteresis + " 天），维持不变");
        }

        // 慢爬升：每次最多 +1 天
        int next = Math.min(currentDays + 1, cfg.maxDays());
        return new CooldownDecision(next, Tier.NORMAL, next != currentDays,
                "容量宽裕（目标 " + target + " 天），慢爬升 +1 天");
    }

    /**
     * 手动模式的安全上限：在当前在库与周转水平下，最多允许设定多少天冷却
     * 而不会使可用率跌破 TIGHT 线。用于 P3001 返回建议值。
     */
    public static int maxSafeDays(SpaceMetrics m, CooldownConfig cfg) {
        // 要求 capacity - inStock - cooling >= capacity * tightThreshold
        // 且稳态 cooling ≈ dailyPickup * days
        double allowedCooling = m.capacity() * (1 - cfg.tightThreshold()) - m.inStock();
        if (allowedCooling <= 0) {
            return cfg.minDays();
        }
        double rate = Math.max(m.dailyPickup(), 1.0);
        return clamp((int) Math.floor(allowedCooling / rate), cfg.minDays(), cfg.maxDays());
    }

    /** 分档强制取值：下调一次到位；若目标高于当前值则维持当前值（不因降档反而延长）。 */
    private static CooldownDecision apply(int currentDays, int forcedDays, Tier tier, String reason) {
        int newDays = Math.min(currentDays, forcedDays);
        return new CooldownDecision(newDays, tier, newDays != currentDays, reason);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
