package com.sf.station.code.application;

import com.sf.station.code.domain.CodeSpace;
import com.sf.station.code.domain.CooldownConfig;
import com.sf.station.common.AppProperties;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

/**
 * 冷却期的派生判定（INV-3 的执行者）。
 *
 * <p>冷却状态<b>不以绝对时间戳落库</b>，而是在判定时由
 * {@code outbound_at + 当前冷却天数} 实时计算。
 * 因此策略天数一改，全量存量记录立即生效，无需任何回刷。
 */
@Service
public class CooldownQueryService {

    private final AppProperties props;

    public CooldownQueryService(AppProperties props) {
        this.props = props;
    }

    public CooldownConfig config() {
        return props.toCooldownConfig();
    }

    /** 该排当前生效的冷却天数，clamp 在 [minDays, maxDays] 内以防配置越界 */
    public int effectiveDays(CodeSpace space) {
        CooldownConfig cfg = config();
        return Math.max(cfg.minDays(), Math.min(cfg.maxDays(), space.getCooldownDays()));
    }

    /**
     * 冷却边界：outboundAt <= boundary 的记录视为冷却完毕，其码可被复用。
     *
     * <p>边界取闭区间，与滞留判定的 {@code >=} 同向，避免两处规则相反。
     */
    public LocalDateTime boundary(CodeSpace space, LocalDateTime now) {
        return now.minusDays(effectiveDays(space));
    }

    /** 该码的预计回炉日期，供 P2003 与取件回执展示 */
    public LocalDateTime reusableAt(CodeSpace space, LocalDateTime outboundAt) {
        return outboundAt == null ? null : outboundAt.plusDays(effectiveDays(space));
    }
}
