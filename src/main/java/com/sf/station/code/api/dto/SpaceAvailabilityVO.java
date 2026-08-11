package com.sf.station.code.api.dto;

import com.sf.station.code.domain.CodeSpace;
import com.sf.station.code.domain.CooldownMode;
import com.sf.station.code.domain.SpaceMetrics;
import com.sf.station.code.domain.Tier;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 一排的可用性快照（文档 §10：容量 / 在库 / 冷却 / 可用 / 冷却天数 / 档位）。
 *
 * <p>{@code cooling} 与 {@code available} 都是<b>实时派生</b>的：
 * 冷却量由 {@code outbound_at} 与当前冷却边界比较得出，冷却天数一改立即变化，
 * 库里不存在也不应存在这两个字段（INV-3）。
 */
@Schema(description = "排的可用性")
public record SpaceAvailabilityVO(
        String prefix,
        @Schema(description = "排内序号上限") int capacity,
        @Schema(description = "在库量") int inStock,
        @Schema(description = "冷却中数量，实时派生") int cooling,
        @Schema(description = "可用数 = 容量 − 在库 − 冷却") int available,
        @Schema(description = "可用率") double availableRatio,
        @Schema(description = "当前生效冷却天数") int cooldownDays,
        @Schema(description = "AUTO | MANUAL") CooldownMode cooldownMode,
        @Schema(description = "NORMAL | TIGHT | EMERGENCY") Tier tier,
        @Schema(description = "近 14 天日均入库 EWMA") double dailyInbound,
        @Schema(description = "近 14 天日均出库 EWMA") double dailyPickup,
        @Schema(description = "下一个可用取件码，仅供展示，不占位") String nextCode,
        @Schema(description = "该排是否启用") boolean enabled) {

    public static SpaceAvailabilityVO of(CodeSpace space, SpaceMetrics m, String nextCode) {
        return new SpaceAvailabilityVO(
                space.getPrefix(), m.capacity(), m.inStock(), m.cooling(), m.available(),
                round(m.availableRatio()),
                space.getCooldownDays(), space.getCooldownMode(), space.getTier(),
                round(m.dailyInbound()), round(m.dailyPickup()),
                nextCode, space.isEnabled());
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
