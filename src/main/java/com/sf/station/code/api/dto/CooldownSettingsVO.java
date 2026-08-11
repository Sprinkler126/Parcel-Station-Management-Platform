package com.sf.station.code.api.dto;

import com.sf.station.code.domain.CooldownConfig;
import com.sf.station.code.domain.CooldownSettings;
import java.time.LocalDateTime;

public record CooldownSettingsVO(
        int minDays,
        int maxDays,
        int bufferDays,
        int defaultDays,
        double tightThreshold,
        double emergencyThreshold,
        double ewmaAlpha,
        int statWindowDays,
        LocalDateTime updatedAt,
        String operator) {

    public static CooldownSettingsVO of(CooldownSettings settings) {
        CooldownConfig config = settings.toConfig();
        return new CooldownSettingsVO(config.minDays(), config.maxDays(), config.bufferDays(),
                config.defaultDays(), config.tightThreshold(), config.emergencyThreshold(),
                config.ewmaAlpha(), config.statWindowDays(), settings.getUpdatedAt(),
                settings.getOperator());
    }
}
