package com.sf.station.code.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 货架排基础设置；前缀是稳定业务标识，不允许修改。 */
@Schema(description = "修改货架排容量与启用状态")
public record CodeSpaceUpdateRequest(
        @Min(value = 1, message = "容量至少为 1")
        @Max(value = 9999, message = "容量不能超过 9999")
        int capacity,

        @NotNull(message = "启用状态不能为空")
        Boolean enabled) {
}
