package com.sf.station.stats;

import com.sf.station.code.api.dto.SpaceAvailabilityVO;
import com.sf.station.code.application.CodeSpaceQueryService;
import com.sf.station.common.AppProperties;
import com.sf.station.parcel.domain.ParcelStatus;
import com.sf.station.parcel.repository.ParcelRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 看板统计（文档 §10：GET /api/v1/stats/today）。
 *
 * <p><b>所有数字都是查询时算的，一个也不落库。</b>今日入库、今日出库、在库、滞留，
 * 看上去都是"适合缓存的聚合值"，但它们各自的失效时机完全不同：
 * 今日计数在零点翻篇、在库量在每次入库出库时变、滞留量随时间自然增长
 * （<b>没有任何写操作，它也会变</b>）。给它们做一致的缓存失效是不可能的，
 * 而不一致的看板比没有看板更糟——站员会按错误的滞留数去做催取。
 *
 * <p>驿站单站规模在千级，全表 count 的代价可以忽略。真到了需要优化的量级，
 * 正确做法是物化视图或读库，而不是在业务表里塞冗余计数字段。
 */
@Service
@Transactional(readOnly = true)
public class StatsService {

    private final ParcelRepository parcelRepo;
    private final CodeSpaceQueryService spaceQuery;
    private final AppProperties props;
    private final Clock clock;

    public StatsService(ParcelRepository parcelRepo, CodeSpaceQueryService spaceQuery,
                        AppProperties props, Clock clock) {
        this.parcelRepo = parcelRepo;
        this.spaceQuery = spaceQuery;
        this.props = props;
        this.clock = clock;
    }

    public TodayStatsVO today() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        LocalDateTime dayStart = today.atStartOfDay();
        LocalDateTime dayEnd = dayStart.plusDays(1);

        LocalDateTime warnBoundary = now.minusHours(props.getOverdue().getWarnHours());
        LocalDateTime alertBoundary = now.minusHours(props.getOverdue().getAlertHours());

        long inStock = parcelRepo.countByStatus(ParcelStatus.PENDING);
        long overdueWarnAndAbove = parcelRepo.countOverdue(warnBoundary);
        long overdueAlert = parcelRepo.countOverdue(alertBoundary);

        List<CourierCountVO> couriers = new ArrayList<>();
        for (Object[] row : parcelRepo.countInStockByCourier()) {
            couriers.add(new CourierCountVO(
                    String.valueOf(row[0]), ((Number) row[1]).longValue()));
        }

        List<SpaceAvailabilityVO> spaces = spaceQuery.availability();

        return new TodayStatsVO(
                today,
                now,
                parcelRepo.countInboundBetween(dayStart, dayEnd),
                parcelRepo.countOutboundBetween(dayStart, dayEnd),
                inStock,
                // WARN 档 = 超 48h 但未超 72h，两次 count 相减即可，无需再写一条 SQL
                overdueWarnAndAbove - overdueAlert,
                overdueAlert,
                overdueWarnAndAbove,
                couriers,
                spaces);
    }
}
