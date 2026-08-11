package com.sf.station.parcel.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 补录真实尾号请求（F14）。 */
@Schema(description = "补录真实尾号请求")
public class SuffixPatchRequest {

    @Schema(description = "真实手机后四位", example = "5678")
    @NotBlank(message = "真实后四位不能为空")
    @Pattern(regexp = "\\d{4}", message = "真实后四位应为 4 位数字")
    private String realSuffix;

    @Schema(description = "操作员", example = "站员A")
    @Size(max = 32, message = "操作员长度不能超过 32")
    private String operator;

    public String getRealSuffix() {
        return realSuffix;
    }

    public void setRealSuffix(String realSuffix) {
        this.realSuffix = realSuffix;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }
}
