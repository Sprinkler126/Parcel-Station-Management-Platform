package com.sf.station.code.api.dto;

import com.sf.station.code.domain.CooldownPolicyLog;
import com.sf.station.code.domain.Tier;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 冷却决策日志条目。
 *
 * <p>带完整指标快照，使"当时为什么这么决定"可以脱离当前数据复现——
 * 只看结论不看输入，日志等于没记。
 */
@Schema(description = "冷却决策日志")
public record CooldownPolicyLogVO(
        Long id,
        String prefix,
        Integer oldDays,
        Integer newDays,
        @Schema(description = "是否发生变更") boolean changed,
        Tier tier,
        Integer capacity,
        Integer inStock,
        Integer cooling,
        Integer available,
        BigDecimal dailyInbound,
        BigDecimal dailyPickup,
        @Schema(description = "决策理由，可解释性的落点") String reason,
        LocalDateTime decidedAt) {

    public static CooldownPolicyLogVO of(CooldownPolicyLog l) {
        boolean changed = l.getOldDays() != null && l.getNewDays() != null
                && !l.getOldDays().equals(l.getNewDays());
        return new CooldownPolicyLogVO(l.getId(), l.getPrefix(), l.getOldDays(), l.getNewDays(),
                changed, l.getTier(), l.getCapacity(), l.getInStock(), l.getCooling(),
                l.getAvailable(), l.getDailyInbound(), l.getDailyPickup(),
                l.getReason(), l.getDecidedAt());
    }
}
