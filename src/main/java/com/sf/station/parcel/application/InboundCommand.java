package com.sf.station.parcel.application;

import com.sf.station.code.domain.AllocScope;
import com.sf.station.parcel.domain.CodeSource;

/**
 * 入库命令（应用层内部模型，与 HTTP DTO 解耦）。
 *
 * @param trackingNo   运单号
 * @param courier      快递公司
 * @param contactNo    面单联系号
 * @param receiverName 收件人姓氏，可选
 * @param codeMode     AUTO / MANUAL
 * @param scope        AUTO 时生效
 * @param codePrefix   ROW 必填；SHELF 填货架号如 "15"；FULL 可空
 * @param pickupCode   MANUAL 必填
 * @param manualSuffix AXB 单可选的真实后四位
 * @param operator     操作员
 * @param remark       备注 / 异常件标记
 */
public record InboundCommand(String trackingNo, String courier, String contactNo,
                             String receiverName, CodeSource codeMode, AllocScope scope,
                             String codePrefix, String pickupCode, String manualSuffix,
                             String operator, String remark) {
}
