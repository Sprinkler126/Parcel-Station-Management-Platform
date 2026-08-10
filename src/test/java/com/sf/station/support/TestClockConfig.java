package com.sf.station.support;

import com.sf.station.common.ClockConfig;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 用 MutableClock 覆盖生产 Clock。
 *
 * <p>固定起点为 2026-03-02 09:00（周一上午，驿站卸货高峰），
 * 使所有依赖时间的断言完全确定。
 */
@TestConfiguration
public class TestClockConfig {

    public static final ZoneId ZONE = ClockConfig.ZONE;
    public static final LocalDateTime START = LocalDateTime.of(2026, 3, 2, 9, 0, 0);

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(START.atZone(ZONE).toInstant(), ZONE);
    }
}
