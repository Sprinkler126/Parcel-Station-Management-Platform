package com.sf.station.parcel.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** 通用操作请求体：取件、撤销、催取、退回、备注共用。 */
@Schema(description = "操作请求")
public class OperationRequest {

    @Schema(description = "操作员", example = "站员A")
    @Size(max = 32, message = "操作员长度不能超过 32")
    private String operator;

    @Schema(description = "备注 / 退回原因")
    @Size(max = 255, message = "备注长度不能超过 255")
    private String remark;

    @Schema(description = "代取人信息，写入流水")
    @Size(max = 64, message = "代取人信息长度不能超过 64")
    private String agent;

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public String getAgent() {
        return agent;
    }

    public void setAgent(String agent) {
        this.agent = agent;
    }
}
