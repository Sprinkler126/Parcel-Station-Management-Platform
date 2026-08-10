package com.sf.station.code.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 冷却期决策日志。每次决策无论是否变更都写一条，含完整指标快照。
 *
 * <p>可解释性是这类自动机制的生命线：站员看到 15-1 是 4 天而 15-2 是 30 天，
 * 必须能查到为什么。
 */
@Entity
@Table(name = "cooldown_policy_log")
public class CooldownPolicyLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "prefix", nullable = false, length = 16)
    private String prefix;

    @Column(name = "old_days")
    private Integer oldDays;

    @Column(name = "new_days")
    private Integer newDays;

    @Enumerated(EnumType.STRING)
    @Column(name = "tier", length = 12)
    private Tier tier;

    @Column(name = "capacity")
    private Integer capacity;

    @Column(name = "in_stock")
    private Integer inStock;

    @Column(name = "cooling")
    private Integer cooling;

    @Column(name = "available")
    private Integer available;

    @Column(name = "daily_inbound", precision = 10, scale = 2)
    private BigDecimal dailyInbound;

    @Column(name = "daily_pickup", precision = 10, scale = 2)
    private BigDecimal dailyPickup;

    @Column(name = "reason", length = 255)
    private String reason;

    @Column(name = "decided_at", nullable = false)
    private LocalDateTime decidedAt;

    protected CooldownPolicyLog() {
        // for JPA
    }

    public static CooldownPolicyLog of(SpaceMetrics m, int oldDays, CooldownDecision d,
                                       LocalDateTime decidedAt) {
        CooldownPolicyLog l = new CooldownPolicyLog();
        l.prefix = m.prefix();
        l.oldDays = oldDays;
        l.newDays = d.newDays();
        l.tier = d.tier();
        l.capacity = m.capacity();
        l.inStock = m.inStock();
        l.cooling = m.cooling();
        l.available = m.available();
        l.dailyInbound = BigDecimal.valueOf(m.dailyInbound()).setScale(2, java.math.RoundingMode.HALF_UP);
        l.dailyPickup = BigDecimal.valueOf(m.dailyPickup()).setScale(2, java.math.RoundingMode.HALF_UP);
        l.reason = d.reason();
        l.decidedAt = decidedAt;
        return l;
    }

    public Long getId() {
        return id;
    }

    public String getPrefix() {
        return prefix;
    }

    public Integer getOldDays() {
        return oldDays;
    }

    public Integer getNewDays() {
        return newDays;
    }

    public Tier getTier() {
        return tier;
    }

    public Integer getCapacity() {
        return capacity;
    }

    public Integer getInStock() {
        return inStock;
    }

    public Integer getCooling() {
        return cooling;
    }

    public Integer getAvailable() {
        return available;
    }

    public BigDecimal getDailyInbound() {
        return dailyInbound;
    }

    public BigDecimal getDailyPickup() {
        return dailyPickup;
    }

    public String getReason() {
        return reason;
    }

    public LocalDateTime getDecidedAt() {
        return decidedAt;
    }
}
