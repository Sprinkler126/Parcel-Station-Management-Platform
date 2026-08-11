package com.sf.station.stats;

import io.swagger.v3.oas.annotations.media.Schema;

/** 在库包裹的快递公司分布。 */
@Schema(description = "快递公司在库分布")
public record CourierCountVO(
        @Schema(description = "快递公司", example = "SF") String courier,
        @Schema(description = "在库件数", example = "37") long count) {
}
