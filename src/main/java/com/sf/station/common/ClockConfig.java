package com.sf.station.common;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * INV-4：所有当前时间取自注入的 Clock。
 *
 * <p>禁止直接调用 {@code LocalDateTime.now()} 或 {@code System.currentTimeMillis()}。
 * 这是时间规则可自动化测试的前提——测试用 MutableClock 推进时钟即可验证 48h / 72h /
 * 冷却期边界，无需 Thread.sleep。
 */
@Configuration
public class ClockConfig {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock clock() {
        return Clock.system(ZONE);
    }
}
