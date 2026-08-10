package com.sf.station.parcel.api.dto;

import com.sf.station.contact.ContactType;
import com.sf.station.contact.PhoneNormalizer;
import com.sf.station.contact.SuffixSource;
import com.sf.station.parcel.domain.CodeSource;
import com.sf.station.parcel.domain.OverdueLevel;
import com.sf.station.parcel.domain.Parcel;
import com.sf.station.parcel.domain.ParcelStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 包裹展示对象。
 *
 * <p>列表必须提供足够的二次核对信息：尾号撞号在一个站点内很常见，
 * 不能只显示一个手机号尾号。故同时给出脱敏号、取件码、运单尾号、
 * 快递公司、收件人姓氏。
 *
 * <p>滞留档位与精确时长在此实时计算，不读任何落库字段（INV-3）。
 */
@Schema(description = "包裹信息")
public record ParcelVO(
        Long id,
        String trackingNo,
        @Schema(description = "运单尾号，用于二次核对") String trackingTail,
        String courier,
        @Schema(description = "脱敏联系号 138****5678") String contactMasked,
        ContactType contactType,
        @Schema(description = "真实后四位，虚拟号未补录时为空") String realSuffix,
        SuffixSource suffixSource,
        @Schema(description = "是否待补录尾号") boolean needsSuffixPatch,
        String receiverName,
        String pickupCode,
        String codePrefix,
        Integer codeSeq,
        CodeSource codeSource,
        @Schema(description = "是否 EMERGENCY 提前复用，列表需打标提醒核对") boolean reuseForced,
        ParcelStatus status,
        @Schema(description = "码槽位是否仍被占用（含冷却中）") boolean slotHeld,
        LocalDateTime inboundAt,
        LocalDateTime outboundAt,
        Integer urgeCount,
        LocalDateTime lastUrgedAt,
        String remark,
        String operator,
        @Schema(description = "滞留档位 NORMAL | WARN | ALERT，实时计算不落库") OverdueLevel overdueLevel,
        @Schema(description = "精确滞留分钟数") Long overdueMinutes,
        @Schema(description = "已滞留 3 天 2 小时") String overdueText) {

    public static ParcelVO of(Parcel p, LocalDateTime now, int warnHours, int alertHours) {
        OverdueLevel level = OverdueLevel.NORMAL;
        long minutes = 0;
        String text = "";

        if (p.getStatus() == ParcelStatus.PENDING && p.getInboundAt() != null) {
            minutes = Duration.between(p.getInboundAt(), now).toMinutes();
            if (minutes < 0) {
                minutes = 0;
            }
            long hours = minutes / 60;
            // 边界含等号：48h 整即标滞留（TC-13）
            if (hours >= alertHours) {
                level = OverdueLevel.ALERT;
            } else if (hours >= warnHours) {
                level = OverdueLevel.WARN;
            }
            text = humanize(minutes);
        }

        String tracking = p.getTrackingNo();
        String tail = tracking == null ? "" :
                tracking.length() <= 4 ? tracking : tracking.substring(tracking.length() - 4);

        return new ParcelVO(
                p.getId(), tracking, tail, p.getCourier(),
                PhoneNormalizer.mask(p.getContactNo(), p.getContactType()),
                p.getContactType(), p.getRealSuffix(), p.getSuffixSource(),
                p.getRealSuffix() == null || p.getRealSuffix().isBlank(),
                p.getReceiverName(),
                p.getPickupCode(), p.getCodePrefix(), p.getCodeSeq(), p.getCodeSource(),
                p.isReuseForced(),
                p.getStatus(), p.holdsSlot(),
                p.getInboundAt(), p.getOutboundAt(),
                p.getUrgeCount(), p.getLastUrgedAt(),
                p.getRemark(), p.getOperator(),
                level, minutes, text);
    }

    /** 已滞留 3 天 2 小时 */
    private static String humanize(long minutes) {
        long days = minutes / (60 * 24);
        long hours = (minutes % (60 * 24)) / 60;
        long mins = minutes % 60;
        StringBuilder sb = new StringBuilder("已滞留 ");
        if (days > 0) {
            sb.append(days).append(" 天 ");
        }
        if (days > 0 || hours > 0) {
            sb.append(hours).append(" 小时");
        } else {
            sb.append(mins).append(" 分钟");
        }
        return sb.toString().trim();
    }
}
