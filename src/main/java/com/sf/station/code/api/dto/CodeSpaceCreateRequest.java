package com.sf.station.code.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 新增货架排。货架号 18、排号 1 最终组成前缀 18-1。 */
@Schema(description = "新增货架排")
public record CodeSpaceCreateRequest(
        @NotBlank(message = "货架号不能为空")
        @Pattern(regexp = "\\d{1,3}", message = "货架号应为 1~3 位数字")
        String shelfNo,

        @NotBlank(message = "排号不能为空")
        @Pattern(regexp = "\\d{1,3}", message = "排号应为 1~3 位数字")
        String rowNo,

        @Min(value = 1, message = "容量至少为 1")
        @Max(value = 9999, message = "容量不能超过 9999")
        int capacity,

        @Schema(description = "初始固定冷却天数；留空使用 AUTO")
        @Min(value = 1, message = "冷却天数至少为 1")
        @Max(value = 365, message = "冷却天数不能超过 365")
        Integer cooldownDays,

        @Size(max = 32, message = "操作员长度不能超过 32")
        String operator) {

    public String prefix() {
        return Integer.parseInt(shelfNo) + "-" + Integer.parseInt(rowNo);
    }
}
