package com.sf.station.code.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 手动设定冷却天数。
 *
 * @param days     null 表示切回 AUTO 自适应模式；非 null 则锁定为该值并转 MANUAL
 * @param operator 操作人，写入决策日志便于追责
 */
public record CooldownSettingRequest(

        @Schema(description = "冷却天数，留空表示切回自适应（AUTO）", example = "5")
        @Min(value = 1, message = "冷却天数至少 1 天")
        @Max(value = 365, message = "冷却天数不得超过 365 天")
        Integer days,

        @Schema(description = "操作人", example = "站长李")
        String operator) {

    /**
     * 这里的 1~365 只是防御性的粗校验，真正的业务范围（3~90）与安全上限
     * 由 CooldownPolicyApplier 依据实时指标判定并回 P3001。
     * 把业务上限写死在注解里会让"上限随在库量变化"这件事失真。
     */
    public boolean isAuto() {
        return days == null;
    }
}
