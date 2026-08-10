package com.sf.station.parcel.api.dto;

import com.sf.station.code.domain.AllocScope;
import com.sf.station.parcel.application.InboundCommand;
import com.sf.station.parcel.domain.CodeSource;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 入库请求体（文档 §10）。 */
@Schema(description = "包裹入库请求")
public class InboundRequest {

    @Schema(description = "运单号", example = "SF1234567890")
    @NotBlank(message = "运单号不能为空")
    @Size(max = 32, message = "运单号长度不能超过 32")
    private String trackingNo;

    @Schema(description = "快递公司", example = "SF")
    @NotBlank(message = "快递公司不能为空")
    @Size(max = 16, message = "快递公司长度不能超过 16")
    private String courier;

    @Schema(description = "面单联系号，支持真实号 / 掩码号 / AXB 虚拟号", example = "138****5678")
    @NotBlank(message = "联系号不能为空")
    @Size(max = 24, message = "联系号长度不能超过 24")
    private String contactNo;

    @Schema(description = "收件人姓氏", example = "张")
    @Size(max = 32, message = "收件人姓名长度不能超过 32")
    private String receiverName;

    @Schema(description = "取件码来源 AUTO | MANUAL", example = "AUTO")
    private CodeSource codeMode = CodeSource.AUTO;

    @Schema(description = "AUTO 时的取码范围 ROW | SHELF | FULL", example = "ROW")
    private AllocScope scope = AllocScope.ROW;

    @Schema(description = "ROW 必填如 15-1；SHELF 填货架号如 15；FULL 可空", example = "15-1")
    private String codePrefix;

    @Schema(description = "MANUAL 必填的完整取件码", example = "15-1-731")
    private String pickupCode;

    @Schema(description = "AXB 虚拟号可选的真实后四位", example = "5678")
    private String manualSuffix;

    @Schema(description = "操作员", example = "站员A")
    @Size(max = 32, message = "操作员长度不能超过 32")
    private String operator;

    @Schema(description = "备注 / 异常件标记")
    @Size(max = 255, message = "备注长度不能超过 255")
    private String remark;

    public InboundCommand toCommand() {
        return new InboundCommand(trackingNo, courier, contactNo, receiverName,
                codeMode == null ? CodeSource.AUTO : codeMode,
                scope == null ? AllocScope.ROW : scope,
                codePrefix, pickupCode, manualSuffix, operator, remark);
    }

    public String getTrackingNo() {
        return trackingNo;
    }

    public void setTrackingNo(String trackingNo) {
        this.trackingNo = trackingNo;
    }

    public String getCourier() {
        return courier;
    }

    public void setCourier(String courier) {
        this.courier = courier;
    }

    public String getContactNo() {
        return contactNo;
    }

    public void setContactNo(String contactNo) {
        this.contactNo = contactNo;
    }

    public String getReceiverName() {
        return receiverName;
    }

    public void setReceiverName(String receiverName) {
        this.receiverName = receiverName;
    }

    public CodeSource getCodeMode() {
        return codeMode;
    }

    public void setCodeMode(CodeSource codeMode) {
        this.codeMode = codeMode;
    }

    public AllocScope getScope() {
        return scope;
    }

    public void setScope(AllocScope scope) {
        this.scope = scope;
    }

    public String getCodePrefix() {
        return codePrefix;
    }

    public void setCodePrefix(String codePrefix) {
        this.codePrefix = codePrefix;
    }

    public String getPickupCode() {
        return pickupCode;
    }

    public void setPickupCode(String pickupCode) {
        this.pickupCode = pickupCode;
    }

    public String getManualSuffix() {
        return manualSuffix;
    }

    public void setManualSuffix(String manualSuffix) {
        this.manualSuffix = manualSuffix;
    }

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
}
