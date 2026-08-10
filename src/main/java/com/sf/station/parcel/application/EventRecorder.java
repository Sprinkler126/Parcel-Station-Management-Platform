package com.sf.station.parcel.application;

import com.sf.station.parcel.domain.EventType;
import com.sf.station.parcel.domain.Parcel;
import com.sf.station.parcel.domain.ParcelEvent;
import com.sf.station.parcel.domain.ParcelStatus;
import com.sf.station.parcel.repository.ParcelEventRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

/**
 * 事件流水记录器（INV-6）。
 *
 * <p>所有状态变更（入库、取件、撤销、退回、催取、补录尾号、槽位释放、强制复用）
 * 一律写 parcel_event，且<b>不覆盖历史</b>。
 */
@Service
public class EventRecorder {

    private final ParcelEventRepository eventRepo;
    private final Clock clock;

    public EventRecorder(ParcelEventRepository eventRepo, Clock clock) {
        this.eventRepo = eventRepo;
        this.clock = clock;
    }

    public ParcelEvent record(Parcel parcel, EventType type, ParcelStatus from,
                              ParcelStatus to, String operator, String detail) {
        return record(parcel.getId(), type, from, to, operator, detail, LocalDateTime.now(clock));
    }

    public ParcelEvent record(Long parcelId, EventType type, ParcelStatus from,
                              ParcelStatus to, String operator, String detail,
                              LocalDateTime occurredAt) {
        return eventRepo.save(ParcelEvent.of(parcelId, type, from, to, operator, detail, occurredAt));
    }
}
