package com.sf.station.parcel.api.dto;

import com.sf.station.parcel.domain.EventType;
import com.sf.station.parcel.domain.ParcelEvent;
import com.sf.station.parcel.domain.ParcelStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 状态流水条目（INV-6）。
 *
 * <p>撤销取件在流水里表现为 PICKUP 与 CANCEL_PICKUP 两条并存，
 * 而不是把 PICKUP 那条抹掉——"曾于某时出库、某时撤销、操作人是谁"必须完整可查。
 */
@Schema(description = "状态流水")
public record ParcelEventVO(
        Long id,
        EventType eventType,
        @Schema(description = "事件中文名") String eventName,
        ParcelStatus fromStatus,
        ParcelStatus toStatus,
        String operator,
        String detail,
        LocalDateTime occurredAt) {

    public static ParcelEventVO of(ParcelEvent e) {
        return new ParcelEventVO(e.getId(), e.getEventType(), name(e.getEventType()),
                e.getFromStatus(), e.getToStatus(), e.getOperator(),
                e.getDetail(), e.getOccurredAt());
    }

    private static String name(EventType t) {
        if (t == null) {
            return "";
        }
        return switch (t) {
            case INBOUND -> "入库";
            case PICKUP -> "确认取件";
            case CANCEL_PICKUP -> "撤销取件";
            case RETURN -> "拒收退回";
            case URGE -> "催取";
            case SUFFIX_PATCH -> "补录尾号";
            case SLOT_RELEASE -> "码槽位回炉";
            case SLOT_FORCE_REUSE -> "强制提前复用";
            case REMARK -> "异常件标记";
        };
    }
}
