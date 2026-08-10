package com.sf.station.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 可推进的测试时钟（INV-4 的测试侧支撑）。
 *
 * <p>有了它，48h / 72h 滞留边界与冷却期边界全部可用"推进时钟"验证，
 * 无需 Thread.sleep，测试可重复且秒级完成。
 */
public class MutableClock extends Clock {

    private volatile Instant instant;
    private final ZoneId zone;

    public MutableClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId z) {
        return new MutableClock(instant, z);
    }

    @Override
    public Instant instant() {
        return instant;
    }

    public void advance(Duration d) {
        this.instant = this.instant.plus(d);
    }

    public void advanceHours(long hours) {
        advance(Duration.ofHours(hours));
    }

    public void advanceDays(long days) {
        advance(Duration.ofDays(days));
    }

    public void advanceMinutes(long minutes) {
        advance(Duration.ofMinutes(minutes));
    }

    public void setInstant(Instant instant) {
        this.instant = instant;
    }
}
