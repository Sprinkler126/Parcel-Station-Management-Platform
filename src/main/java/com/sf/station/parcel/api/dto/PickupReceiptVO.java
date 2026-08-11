package com.sf.station.parcel.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 取件回执（文档 §2 F3 输出：出库时间 + 该码预计回炉日期）。
 *
 * <p>回炉日期是<b>按当前冷却策略实时算出</b>的展示值，不落库。
 * 策略若在此之后调整，实际可复用时间随之改变——这正是 INV-3 的效果，
 * 故字段命名为"预计"。
 */
@Schema(description = "取件回执")
public record PickupReceiptVO(
        ParcelVO parcel,
        @Schema(description = "出库时间") LocalDateTime outboundAt,
        @Schema(description = "该码预计回炉日期，按当前冷却策略实时推算") LocalDateTime codeReusableAt) {
}
