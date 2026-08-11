package com.sf.station.code.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** 全站唯一的冷却策略参数，数据库是运行时唯一配置源。 */
@Entity
@Table(name = "cooldown_settings")
public class CooldownSettings {

    public static final String GLOBAL_ID = "GLOBAL";

    @Id
    @Column(name = "id", length = 16)
    private String id;
    @Column(name = "min_days", nullable = false)
    private int minDays;
    @Column(name = "max_days", nullable = false)
    private int maxDays;
    @Column(name = "buffer_days", nullable = false)
    private int bufferDays;
    @Column(name = "default_days", nullable = false)
    private int defaultDays;
    @Column(name = "tight_threshold", nullable = false)
    private double tightThreshold;
    @Column(name = "emergency_threshold", nullable = false)
    private double emergencyThreshold;
    @Column(name = "ewma_alpha", nullable = false)
    private double ewmaAlpha;
    @Column(name = "stat_window_days", nullable = false)
    private int statWindowDays;
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
    @Column(name = "operator", length = 32)
    private String operator;

    protected CooldownSettings() {
    }

    public static CooldownSettings of(CooldownConfig config, LocalDateTime now, String operator) {
        CooldownSettings value = new CooldownSettings();
        value.id = GLOBAL_ID;
        value.update(config, now, operator);
        return value;
    }

    public void update(CooldownConfig config, LocalDateTime now, String operator) {
        minDays = config.minDays();
        maxDays = config.maxDays();
        bufferDays = config.bufferDays();
        defaultDays = config.defaultDays();
        tightThreshold = config.tightThreshold();
        emergencyThreshold = config.emergencyThreshold();
        ewmaAlpha = config.ewmaAlpha();
        statWindowDays = config.statWindowDays();
        updatedAt = now;
        this.operator = operator;
    }

    public CooldownConfig toConfig() {
        return new CooldownConfig(minDays, maxDays, bufferDays, defaultDays,
                tightThreshold, emergencyThreshold, ewmaAlpha, statWindowDays);
    }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public String getOperator() { return operator; }
}
