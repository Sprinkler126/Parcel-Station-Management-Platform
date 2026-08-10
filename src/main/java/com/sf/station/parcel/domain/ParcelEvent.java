package com.sf.station.parcel.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 状态流水（INV-6）。只追加不修改，撤销以反向事件表达。
 */
@Entity
@Table(name = "parcel_event")
public class ParcelEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "parcel_id", nullable = false)
    private Long parcelId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 24)
    private EventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 16)
    private ParcelStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", length = 16)
    private ParcelStatus toStatus;

    @Column(name = "operator", length = 32)
    private String operator;

    @Column(name = "detail", length = 512)
    private String detail;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    protected ParcelEvent() {
        // for JPA
    }

    public static ParcelEvent of(Long parcelId, EventType type, ParcelStatus from,
                                 ParcelStatus to, String operator, String detail,
                                 LocalDateTime occurredAt) {
        ParcelEvent e = new ParcelEvent();
        e.parcelId = parcelId;
        e.eventType = type;
        e.fromStatus = from;
        e.toStatus = to;
        e.operator = operator;
        e.detail = detail != null && detail.length() > 512 ? detail.substring(0, 512) : detail;
        e.occurredAt = occurredAt;
        return e;
    }

    public Long getId() {
        return id;
    }

    public Long getParcelId() {
        return parcelId;
    }

    public EventType getEventType() {
        return eventType;
    }

    public ParcelStatus getFromStatus() {
        return fromStatus;
    }

    public ParcelStatus getToStatus() {
        return toStatus;
    }

    public String getOperator() {
        return operator;
    }

    public String getDetail() {
        return detail;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }
}
