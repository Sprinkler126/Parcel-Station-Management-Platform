package com.sf.station.parcel.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 批量取件请求。
 *
 * <p>两种用法二选一：
 * <ul>
 *   <li>传 {@code realSuffix}：按真实后四位聚合该客户的全部在库包裹一次取走（F9 主路径）；</li>
 *   <li>传 {@code ids}：前端已勾选具体条目时直接传 ID 列表。</li>
 * </ul>
 * 两者都给时以 {@code ids} 为准。
 */
@Schema(description = "批量取件请求")
public class BatchPickupRequest {

    @Schema(description = "真实手机后四位，按此聚合同一客户的多件包裹", example = "5678")
    private String realSuffix;

    @Schema(description = "包裹 ID 列表，优先于 realSuffix")
    @Size(max = 200, message = "单次批量取件不超过 200 件")
    private List<Long> ids;

    @Schema(description = "操作员", example = "站员A")
    @Size(max = 32, message = "操作员长度不能超过 32")
    private String operator;

    @Schema(description = "整批请求幂等标识；服务端按 requestId:parcelId 派生单件幂等键",
            example = "batch-pickup-20260811-001")
    @NotBlank(message = "requestId 不能为空")
    @Size(max = 64, message = "requestId 长度不能超过 64")
    private String requestId;

    public String getRealSuffix() {
        return realSuffix;
    }

    public void setRealSuffix(String realSuffix) {
        this.realSuffix = realSuffix;
    }

    public List<Long> getIds() {
        return ids;
    }

    public void setIds(List<Long> ids) {
        this.ids = ids;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }
}
