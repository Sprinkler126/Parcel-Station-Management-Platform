package com.sf.station.code.api.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CooldownSettingsRequest(
        @NotNull @Min(1) @Max(30) Integer minDays,
        @NotNull @Min(3) @Max(365) Integer maxDays,
        @NotNull @Min(0) @Max(30) Integer bufferDays,
        @NotNull @Min(1) @Max(365) Integer defaultDays,
        @NotNull @DecimalMin("0.01") @DecimalMax("0.95") Double tightThreshold,
        @NotNull @DecimalMin("0.01") @DecimalMax("0.90") Double emergencyThreshold,
        @NotNull @DecimalMin("0.05") @DecimalMax("1.00") Double ewmaAlpha,
        @NotNull @Min(1) @Max(90) Integer statWindowDays,
        @Size(max = 32) String operator) {
}
