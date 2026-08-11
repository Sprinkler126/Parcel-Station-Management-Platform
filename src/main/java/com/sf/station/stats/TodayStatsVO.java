package com.sf.station.stats;

import com.sf.station.code.api.dto.SpaceAvailabilityVO;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 今日看板。
 *
 * <p>{@code statAt} 必须回给前端：所有滞留数字都是相对这一时刻算的，
 * 前端页面停在那里不刷新时，用户至少能看到"这是几点几分的数据"。
 */
@Schema(description = "今日看板")
public record TodayStatsVO(

        @Schema(description = "统计日期") LocalDate date,
        @Schema(description = "统计时刻，滞留数字均相对此刻计算") LocalDateTime statAt,

        @Schema(description = "今日入库") long inboundToday,
        @Schema(description = "今日出库") long outboundToday,
        @Schema(description = "当前在库") long inStock,

        @Schema(description = "滞留 48~72 小时") long overdueWarn,
        @Schema(description = "滞留超 72 小时") long overdueAlert,
        @Schema(description = "滞留合计（超 48 小时）") long overdueTotal,

        @Schema(description = "在库快递公司分布") List<CourierCountVO> couriers,
        @Schema(description = "各排可用性") List<SpaceAvailabilityVO> spaces) {
}
