package com.sf.station.code.application;

import com.sf.station.code.domain.CodeSpace;
import com.sf.station.code.domain.SpaceMetrics;
import com.sf.station.common.AppProperties;
import com.sf.station.parcel.repository.ParcelRepository;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 指标采集：把数据库里的原始事实汇总成 {@link SpaceMetrics} 快照。
 *
 * <p>这是"策略与执行分离"的执行侧一半：本类只读数据、只算指标，
 * <b>不做任何决策</b>；决策全部落在纯函数 {@code CooldownPolicy} 里。
 * 分开之后，滞回、非对称响应、分档切换可以用不带 Spring 上下文的单测覆盖。
 */
@Service
@Transactional(readOnly = true)
public class SpaceMetricsService {

    private final ParcelRepository parcelRepo;
    private final CooldownQueryService cooldownQuery;
    private final AppProperties props;
    private final Clock clock;

    public SpaceMetricsService(ParcelRepository parcelRepo, CooldownQueryService cooldownQuery,
                               AppProperties props, Clock clock) {
        this.parcelRepo = parcelRepo;
        this.cooldownQuery = cooldownQuery;
        this.props = props;
        this.clock = clock;
    }

    public SpaceMetrics collect(CodeSpace space) {
        return collect(space, LocalDateTime.now(clock));
    }

    /**
     * 采集一排的指标快照。
     *
     * <p>槽位占用量 = 在库量 + 冷却量。冷却量同样是<b>派生</b>出来的——
     * 由 {@code outbound_at} 与当前冷却边界实时比较，而非读某个"冷却截止"字段（INV-3）。
     */
    public SpaceMetrics collect(CodeSpace space, LocalDateTime now) {
        LocalDateTime boundary = cooldownQuery.boundary(space, now);
        int inStock = parcelRepo.countInStockByPrefix(space.getPrefix());
        int cooling = parcelRepo.countCoolingByPrefix(space.getPrefix(), boundary);

        int window = props.getCooldown().getStatWindowDays();
        LocalDateTime from = now.minusDays(window);
        double dailyInbound = ewma(parcelRepo.dailyInboundCounts(space.getPrefix(), from),
                now.toLocalDate(), window);
        double dailyPickup = ewma(parcelRepo.dailyOutboundCounts(space.getPrefix(), from),
                now.toLocalDate(), window);

        return new SpaceMetrics(space.getPrefix(), space.getCapacity(),
                inStock, cooling, dailyInbound, dailyPickup);
    }

    /**
     * 近 N 天日计数的指数加权移动平均。
     *
     * <p><b>没有数据的日子必须补 0 参与计算</b>，否则一排停摆三天后
     * EWMA 会保持在停摆前的高位，据此算出的冷却期偏短，白白浪费码空间。
     * 用 {@code group by} 的结果直接平均正是这个坑。
     *
     * <p>α 取 0.3：近 3~4 天的样本占约 76% 权重，既能跟上双十一这类周期性起量，
     * 又不会被单日异常值带偏。
     */
    private double ewma(List<Object[]> dailyCounts, LocalDate today, int window) {
        Map<LocalDate, Long> byDay = new HashMap<>();
        for (Object[] row : dailyCounts) {
            LocalDate d = toLocalDate(row[0]);
            if (d != null) {
                byDay.put(d, ((Number) row[1]).longValue());
            }
        }
        double alpha = props.getCooldown().getEwmaAlpha();
        double ewma = 0;
        boolean seeded = false;
        // 从最早一天推到今天，缺失日补 0
        for (int i = window - 1; i >= 0; i--) {
            double v = byDay.getOrDefault(today.minusDays(i), 0L);
            ewma = seeded ? alpha * v + (1 - alpha) * ewma : v;
            seeded = true;
        }
        return ewma;
    }

    /** JPA 的 {@code cast(x as date)} 在 H2 与 MySQL 上分别返回 LocalDate 与 java.sql.Date */
    private static LocalDate toLocalDate(Object o) {
        if (o instanceof LocalDate d) {
            return d;
        }
        if (o instanceof Date d) {
            return d.toLocalDate();
        }
        if (o instanceof LocalDateTime dt) {
            return dt.toLocalDate();
        }
        return null;
    }
}
