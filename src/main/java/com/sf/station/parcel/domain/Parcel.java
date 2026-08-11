package com.sf.station.parcel.domain;

import com.sf.station.contact.ContactType;
import com.sf.station.contact.SuffixSource;
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
 * 包裹实体。
 *
 * <p><b>INV-1｜两个生命周期相互独立</b>：
 * <ul>
 *   <li>运单生命周期由 {@code activeFlag} 表达：PENDING 为 1，终态 NULL。
 *       配合 {@code uk_tracking_active} 唯一索引实现"未完结唯一"——
 *       同一运单号允许多条终态记录（拒收重投、取错退回），但只允许一条未完结。</li>
 *   <li>码槽位生命周期由 {@code codeSlotFlag} 表达：占用或冷却中为 1，回炉后 NULL。
 *       配合 {@code uk_code_slot} 唯一索引保证同一个码同一时刻只有一个持有者。</li>
 * </ul>
 * 包裹取走时 activeFlag 立即置 NULL，但 codeSlotFlag 仍为 1 直到冷却结束。
 * <b>用一个字段承担两者会直接导致冷却失效</b>，这是本系统最容易写错的地方。
 *
 * <p><b>INV-3｜只落原始事实，不落策略推论</b>：本表存 {@code outboundAt}（事实），
 * 不存"何时可复用"（当前冷却策略的推论）。滞留同理，实时计算不落库。
 */
@Entity
@Table(name = "parcel")
public class Parcel {

    /** 唯一索引名，用于从 DataIntegrityViolationException 中区分冲突类型 */
    public static final String UK_TRACKING_ACTIVE = "uk_tracking_active";
    public static final String UK_CODE_SLOT = "uk_code_slot";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tracking_no", nullable = false, length = 32)
    private String trackingNo;

    @Column(name = "courier", nullable = false, length = 16)
    private String courier;

    // ---------- 联系方式：双职责拆分 ----------

    /** 面单原始号码，可能是真实号、掩码号或 AXB 虚拟号 */
    @Column(name = "contact_no", nullable = false, length = 24)
    private String contactNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "contact_type", nullable = false, length = 8)
    private ContactType contactType;

    /** 客户口头报出的真实手机号后四位，检索主键。AXB 单入库时可能未知，允许后续补录 */
    @Column(name = "real_suffix", length = 4)
    private String realSuffix;

    @Enumerated(EnumType.STRING)
    @Column(name = "suffix_source", length = 12)
    private SuffixSource suffixSource;

    @Column(name = "receiver_name", length = 32)
    private String receiverName;

    // ---------- 取件码 ----------

    /** 完整取件码，如 15-1-7231，已归一化 */
    @Column(name = "pickup_code", nullable = false, length = 24)
    private String pickupCode;

    /** 排前缀 15-1，码空间划分单位，所有分配与统计的维度 */
    @Column(name = "code_prefix", nullable = false, length = 16)
    private String codePrefix;

    /** 排内序号，1 ~ capacity */
    @Column(name = "code_seq", nullable = false)
    private Integer codeSeq;

    @Enumerated(EnumType.STRING)
    @Column(name = "code_source", nullable = false, length = 12)
    private CodeSource codeSource;

    /** 1=占用或冷却中，NULL=已回炉。见 INV-1 */
    @Column(name = "code_slot_flag", columnDefinition = "tinyint")
    private Integer codeSlotFlag;

    /** 1=EMERGENCY 档提前复用，列表页需显示"提前复用"标记提醒核对 */
    @Column(name = "code_reuse_forced", columnDefinition = "tinyint")
    private Integer codeReuseForced;

    // ---------- 状态 ----------

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ParcelStatus status;

    /** 1=未完结，NULL=终态。见 INV-1 */
    @Column(name = "active_flag", columnDefinition = "tinyint")
    private Integer activeFlag;

    @Column(name = "inbound_at", nullable = false)
    private LocalDateTime inboundAt;

    /** 出库时间（取件或退回）。原始事实，冷却判定以此实时推算 */
    @Column(name = "outbound_at")
    private LocalDateTime outboundAt;

    /** 成功取件请求的幂等键；批量取件按 requestId:parcelId 派生。 */
    @Column(name = "pickup_request_id", length = 96)
    private String pickupRequestId;

    @Column(name = "urge_count")
    private Integer urgeCount;

    @Column(name = "last_urged_at")
    private LocalDateTime lastUrgedAt;

    @Column(name = "remark", length = 255)
    private String remark;

    @Column(name = "operator", length = 32)
    private String operator;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected Parcel() {
        // for JPA
    }

    /**
     * 构造一条新入库记录。两个生命周期标记同时置 1：运单未完结、码槽位占用。
     */
    public static Parcel newInbound(String trackingNo, String courier,
                                    String contactNo, ContactType contactType,
                                    String realSuffix, SuffixSource suffixSource,
                                    String receiverName,
                                    String pickupCode, String codePrefix, int codeSeq,
                                    CodeSource codeSource, boolean reuseForced,
                                    String operator, String remark, LocalDateTime now) {
        Parcel p = new Parcel();
        p.trackingNo = trackingNo;
        p.courier = courier;
        p.contactNo = contactNo;
        p.contactType = contactType;
        p.realSuffix = realSuffix;
        p.suffixSource = suffixSource;
        p.receiverName = receiverName;
        p.pickupCode = pickupCode;
        p.codePrefix = codePrefix;
        p.codeSeq = codeSeq;
        p.codeSource = codeSource;
        p.codeSlotFlag = 1;          // 码槽位占用
        p.codeReuseForced = reuseForced ? 1 : 0;
        p.status = ParcelStatus.PENDING;
        p.activeFlag = 1;            // 运单未完结
        p.inboundAt = now;
        p.urgeCount = 0;
        p.operator = operator;
        p.remark = remark;
        p.createdAt = now;
        p.updatedAt = now;
        return p;
    }

    // ---------- 派生属性（不落库，见 INV-3） ----------

    /** 是否在库 */
    public boolean inStock() {
        return status == ParcelStatus.PENDING;
    }

    /** 码槽位是否仍被本行占用（在库中，或已出库但冷却未满） */
    public boolean holdsSlot() {
        return codeSlotFlag != null && codeSlotFlag == 1;
    }

    public boolean isReuseForced() {
        return codeReuseForced != null && codeReuseForced == 1;
    }

    // ---------- getters / setters ----------

    public Long getId() {
        return id;
    }

    public String getTrackingNo() {
        return trackingNo;
    }

    public String getCourier() {
        return courier;
    }

    public String getContactNo() {
        return contactNo;
    }

    public ContactType getContactType() {
        return contactType;
    }

    public String getRealSuffix() {
        return realSuffix;
    }

    public void setRealSuffix(String realSuffix) {
        this.realSuffix = realSuffix;
    }

    public SuffixSource getSuffixSource() {
        return suffixSource;
    }

    public void setSuffixSource(SuffixSource suffixSource) {
        this.suffixSource = suffixSource;
    }

    public String getReceiverName() {
        return receiverName;
    }

    public String getPickupCode() {
        return pickupCode;
    }

    public String getCodePrefix() {
        return codePrefix;
    }

    public Integer getCodeSeq() {
        return codeSeq;
    }

    public CodeSource getCodeSource() {
        return codeSource;
    }

    public Integer getCodeSlotFlag() {
        return codeSlotFlag;
    }

    public void setCodeSlotFlag(Integer codeSlotFlag) {
        this.codeSlotFlag = codeSlotFlag;
    }

    public Integer getCodeReuseForced() {
        return codeReuseForced;
    }

    public ParcelStatus getStatus() {
        return status;
    }

    public Integer getActiveFlag() {
        return activeFlag;
    }

    public LocalDateTime getInboundAt() {
        return inboundAt;
    }

    public LocalDateTime getOutboundAt() {
        return outboundAt;
    }

    public String getPickupRequestId() {
        return pickupRequestId;
    }

    public Integer getUrgeCount() {
        return urgeCount;
    }

    public LocalDateTime getLastUrgedAt() {
        return lastUrgedAt;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public String getOperator() {
        return operator;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
