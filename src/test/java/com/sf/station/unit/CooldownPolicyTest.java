package com.sf.station.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.sf.station.code.domain.CooldownConfig;
import com.sf.station.code.domain.CooldownDecision;
import com.sf.station.code.domain.CooldownPolicy;
import com.sf.station.code.domain.SpaceMetrics;
import com.sf.station.code.domain.Tier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CooldownPolicyTest {

    private final CooldownConfig config = CooldownConfig.defaults();

    @Test
    @DisplayName("TC-18 容量宽裕时冷却天数每次仅增加一天")
    void tc18_increasesCooldownSlowly() {
        SpaceMetrics metrics = new SpaceMetrics("15-1", 1_000, 100, 100, 10, 20);

        CooldownDecision decision = CooldownPolicy.decide(metrics, 7, config);

        assertThat(decision.newDays()).isEqualTo(8);
        assertThat(decision.changed()).isTrue();
        assertThat(decision.tier()).isEqualTo(Tier.NORMAL);
    }

    @Test
    @DisplayName("TC-19 可用率跌破 30% 时一次压缩到最短冷却期")
    void tc19_reducesCooldownQuickly() {
        SpaceMetrics metrics = new SpaceMetrics("15-1", 100, 60, 11, 10, 10);

        CooldownDecision decision = CooldownPolicy.decide(metrics, 14, config);

        assertThat(decision.newDays()).isEqualTo(config.minDays());
        assertThat(decision.changed()).isTrue();
        assertThat(decision.tier()).isEqualTo(Tier.TIGHT);
    }

    @Test
    @DisplayName("TC-20 目标值处于滞回带内时维持原冷却天数")
    void tc20_ignoresSmallFluctuationInsideHysteresisBand() {
        SpaceMetrics metrics = new SpaceMetrics("15-1", 1_000, 700, 0, 10, 24);

        CooldownDecision decision = CooldownPolicy.decide(metrics, 10, config);

        assertThat(decision.newDays()).isEqualTo(10);
        assertThat(decision.changed()).isFalse();
        assertThat(decision.tier()).isEqualTo(Tier.NORMAL);
    }
}
