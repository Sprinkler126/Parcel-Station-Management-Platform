package com.sf.station.code.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 一排的码空间配置。prefix 形如 15-1，语义为"15 号货架第 1 排"。
 *
 * <p>cursorPos 是 next-fit 分配游标。<b>游标不加锁</b>：它的正确性不影响系统正确性，
 * 只影响复用间隔的质量。并发丢失更新最坏是少前进一格，位图会自然跳过已占用位。
 * 识别出哪些状态是"尽力而为"的、不必用强一致手段保护，是控制复杂度的关键。
 */
@Entity
@Table(name = "code_space")
public class CodeSpace {

    /** 排前缀，如 15-1 */
    @Id
    @Column(name = "prefix", length = 16)
    private String prefix;

    /** 排内序号上限，默认 9999 */
    @Column(name = "capacity", nullable = false)
    private int capacity;

    /** next-fit 游标，尽力而为，不加锁 */
    @Column(name = "cursor_pos", nullable = false)
    private int cursorPos;

    @Enumerated(EnumType.STRING)
    @Column(name = "cooldown_mode", nullable = false, length = 8)
    private CooldownMode cooldownMode;

    /** 当前生效的冷却天数 */
    @Column(name = "cooldown_days", nullable = false)
    private int cooldownDays;

    @Enumerated(EnumType.STRING)
    @Column(name = "tier", nullable = false, length = 12)
    private Tier tier;

    @Column(name = "enabled", nullable = false)
    private int enabled;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected CodeSpace() {
        // for JPA
    }

    public static CodeSpace of(String prefix, int capacity, int cooldownDays, LocalDateTime now) {
        CodeSpace s = new CodeSpace();
        s.prefix = prefix;
        s.capacity = capacity;
        s.cursorPos = 0;
        s.cooldownMode = CooldownMode.AUTO;
        s.cooldownDays = cooldownDays;
        s.tier = Tier.NORMAL;
        s.enabled = 1;
        s.updatedAt = now;
        return s;
    }

    /** 货架号，15-1 → 15 */
    public String shelf() {
        int i = prefix.lastIndexOf('-');
        return i < 0 ? prefix : prefix.substring(0, i);
    }

    public boolean isEnabled() {
        return enabled == 1;
    }

    public String getPrefix() {
        return prefix;
    }

    public int getCapacity() {
        return capacity;
    }

    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }

    public int getCursorPos() {
        return cursorPos;
    }

    public void setCursorPos(int cursorPos) {
        this.cursorPos = cursorPos;
    }

    public CooldownMode getCooldownMode() {
        return cooldownMode;
    }

    public void setCooldownMode(CooldownMode cooldownMode) {
        this.cooldownMode = cooldownMode;
    }

    public int getCooldownDays() {
        return cooldownDays;
    }

    public void setCooldownDays(int cooldownDays) {
        this.cooldownDays = cooldownDays;
    }

    public Tier getTier() {
        return tier;
    }

    public void setTier(Tier tier) {
        this.tier = tier;
    }

    public int getEnabled() {
        return enabled;
    }

    public void setEnabled(int enabled) {
        this.enabled = enabled;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
