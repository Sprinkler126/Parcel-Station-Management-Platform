package com.sf.station.parcel.application;

import com.sf.station.parcel.domain.OverdueLevel;
import com.sf.station.parcel.domain.ParcelStatus;

/**
 * 查询条件（应用层内部模型）。
 *
 * @param keyword     查询串，可空表示不限
 * @param channel     检索通道，AUTO 表示由形态自动判断
 * @param status      状态过滤，可空
 * @param overdue     滞留档位过滤，可空。<b>此条件在 SQL 侧以 inbound_at 区间表达</b>，
 *                    不是读某个落库字段（INV-3）
 * @param codePrefix  排前缀过滤，可空
 * @param page        页码，从 0 开始
 * @param size        每页条数
 */
public record ParcelQuery(String keyword, SearchChannel channel, ParcelStatus status,
                          OverdueLevel overdue, String codePrefix, int page, int size) {

    public ParcelQuery {
        page = Math.max(0, page);
        size = size <= 0 ? 20 : Math.min(size, 200);
        channel = channel == null ? SearchChannel.AUTO : channel;
    }

    public boolean hasKeyword() {
        return keyword != null && !keyword.isBlank();
    }

    /** 实际生效的通道：AUTO 时按形态判断 */
    public SearchChannel effectiveChannel() {
        return channel == SearchChannel.AUTO ? SearchChannel.detect(keyword) : channel;
    }
}
