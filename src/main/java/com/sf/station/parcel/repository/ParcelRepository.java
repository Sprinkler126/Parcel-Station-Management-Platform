package com.sf.station.parcel.repository;

import com.sf.station.parcel.domain.Parcel;
import com.sf.station.parcel.domain.ParcelStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 包裹仓储。
 *
 * <p>继承 {@link JpaSpecificationExecutor} 承载分层检索：检索通道、状态、滞留档位、
 * 排前缀是四个正交的可选条件，若用 {@code @Query} 拼 {@code :x is null or ...}，
 * 枚举类型的 null 参数在 Hibernate 里需要显式 cast，可读性与稳定性都差；
 * Criteria 动态拼装则天然只拼出现的条件，生成的 SQL 也更干净。
 */
public interface ParcelRepository extends JpaRepository<Parcel, Long>,
        JpaSpecificationExecutor<Parcel> {

    // =========================================================================
    // 分配路径：位图加载
    // =========================================================================

    /**
     * 加载该排"真正不可用"的序号：在库中，或已出库但仍在冷却期内。
     *
     * <p><b>INV-3 的核心体现</b>：占用判定不读任何"可复用时间"字段，而是由
     * {@code outboundAt} 这一原始事实与传入的 boundary 实时比较得出。
     * boundary = now − effectiveCooldownDays。策略天数一改，全量存量记录立即生效，零回刷。
     *
     * <p>{@code outboundAt is null} 表示在库，一律占用；
     * {@code outboundAt > boundary} 表示冷却未满。
     * 边界取闭区间：outboundAt <= boundary 视为冷却完毕（与滞留的 >= 同向）。
     */
    @Query("""
            select p.codeSeq from Parcel p
            where p.codePrefix = :prefix and p.codeSlotFlag = 1
              and (p.outboundAt is null or p.outboundAt > :boundary)
            """)
    List<Integer> findOccupiedSeqs(@Param("prefix") String prefix,
                                   @Param("boundary") LocalDateTime boundary);

    /**
     * 按需自愈：冷却已过但 code_slot_flag 未被回炉任务清理时，定向释放该槽位，
     * 避免后续 insert 撞 uk_code_slot 唯一索引。
     *
     * <p>与回炉任务构成双保险：任务延迟不阻塞入库，分配疏漏由任务兜底。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Parcel p set p.codeSlotFlag = null, p.updatedAt = :now
            where p.codePrefix = :prefix and p.codeSeq = :seq and p.codeSlotFlag = 1
              and p.outboundAt is not null and p.outboundAt <= :boundary
            """)
    int releaseSlotIfCooled(@Param("prefix") String prefix, @Param("seq") int seq,
                            @Param("boundary") LocalDateTime boundary, @Param("now") LocalDateTime now);

    /** 回炉任务：批量释放该排已过冷却期的槽位 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Parcel p set p.codeSlotFlag = null, p.updatedAt = :now
            where p.codePrefix = :prefix and p.codeSlotFlag = 1
              and p.outboundAt is not null and p.outboundAt <= :boundary
            """)
    int bulkReleaseCooled(@Param("prefix") String prefix, @Param("boundary") LocalDateTime boundary,
                          @Param("now") LocalDateTime now);

    /** 回炉任务：列出将被释放的行，用于写 SLOT_RELEASE 流水 */
    @Query("""
            select p from Parcel p
            where p.codePrefix = :prefix and p.codeSlotFlag = 1
              and p.outboundAt is not null and p.outboundAt <= :boundary
            """)
    List<Parcel> findCooledSlots(@Param("prefix") String prefix,
                                 @Param("boundary") LocalDateTime boundary);

    /**
     * EMERGENCY 档强制复用：选 outbound_at 最早的已出库码。
     *
     * <p><b>永远不得抢占在库包裹的码</b>，故强制 {@code outboundAt is not null}。
     */
    @Query("""
            select p from Parcel p
            where p.codePrefix = :prefix and p.codeSlotFlag = 1 and p.outboundAt is not null
            order by p.outboundAt asc
            """)
    List<Parcel> findForceReuseCandidates(@Param("prefix") String prefix, Pageable pageable);

    /** 当前占用该码槽位的行（用于生成 P2002 / P2003 的差异化提示） */
    @Query("select p from Parcel p where p.pickupCode = :code and p.codeSlotFlag = 1")
    Optional<Parcel> findSlotHolder(@Param("code") String code);

    // =========================================================================
    // 状态流转：INV-5 带前置条件的原子更新
    // =========================================================================

    /**
     * 确认取件。
     *
     * <p><b>INV-1</b>：activeFlag 置 NULL 释放运单唯一槽位，
     * 但 codeSlotFlag 保持为 1 进入冷却期——两个生命周期在此分岔。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Parcel p set p.status = com.sf.station.parcel.domain.ParcelStatus.PICKED_UP,
                                p.activeFlag = null, p.outboundAt = :now,
                                p.operator = :op, p.updatedAt = :now
            where p.id = :id and p.status = com.sf.station.parcel.domain.ParcelStatus.PENDING
            """)
    int markPickedUp(@Param("id") Long id, @Param("now") LocalDateTime now, @Param("op") String op);

    /** 拒收退回。码槽位处理与取件一致：同样进入冷却，因为客户手里的旧通知同样存在。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Parcel p set p.status = com.sf.station.parcel.domain.ParcelStatus.RETURNED,
                                p.activeFlag = null, p.outboundAt = :now,
                                p.operator = :op, p.updatedAt = :now,
                                p.remark = :remark
            where p.id = :id and p.status = com.sf.station.parcel.domain.ParcelStatus.PENDING
            """)
    int markReturned(@Param("id") Long id, @Param("now") LocalDateTime now,
                     @Param("op") String op, @Param("remark") String remark);

    /**
     * 撤销取件：回到 PENDING，重新占用码槽位、恢复 activeFlag、清空出库时间。
     *
     * <p>不覆盖历史——outbound_at 虽被清空，但 PICKUP 与 CANCEL_PICKUP 两条事件
     * 完整保留了"曾于某时出库、某时撤销、操作人是谁"（INV-6）。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Parcel p set p.status = com.sf.station.parcel.domain.ParcelStatus.PENDING,
                                p.activeFlag = 1, p.codeSlotFlag = 1, p.outboundAt = null,
                                p.operator = :op, p.updatedAt = :now
            where p.id = :id and p.status = com.sf.station.parcel.domain.ParcelStatus.PICKED_UP
            """)
    int markCancelPickup(@Param("id") Long id, @Param("now") LocalDateTime now, @Param("op") String op);

    /** 催取 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Parcel p set p.urgeCount = p.urgeCount + 1, p.lastUrgedAt = :now, p.updatedAt = :now
            where p.id = :id and p.status = com.sf.station.parcel.domain.ParcelStatus.PENDING
            """)
    int markUrged(@Param("id") Long id, @Param("now") LocalDateTime now);

    /** 补录真实尾号 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Parcel p set p.realSuffix = :suffix,
                                p.suffixSource = com.sf.station.contact.SuffixSource.MANUAL,
                                p.updatedAt = :now
            where p.id = :id
            """)
    int patchSuffix(@Param("id") Long id, @Param("suffix") String suffix, @Param("now") LocalDateTime now);

    /** 异常件备注。备注不改变状态，故无状态前置条件 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Parcel p set p.remark = :remark, p.updatedAt = :now where p.id = :id")
    int patchRemark(@Param("id") Long id, @Param("remark") String remark, @Param("now") LocalDateTime now);

    // =========================================================================
    // 检索
    // =========================================================================

    /** 同一运单号的未完结记录（用于生成 P2001 的友好提示，不作为唯一性防线） */
    @Query("select p from Parcel p where p.trackingNo = :no and p.activeFlag = 1")
    Optional<Parcel> findActiveByTrackingNo(@Param("no") String no);

    /**
     * 按真实后四位等值匹配走 idx_suffix。
     *
     * <p>禁止 {@code like '%1234'}：前缀不确定，无法利用 B+ 树定位，必然全索引扫描。
     */
    Page<Parcel> findByRealSuffixAndStatus(String realSuffix, ParcelStatus status, Pageable pageable);

    Page<Parcel> findByRealSuffix(String realSuffix, Pageable pageable);

    /** 批量取件的聚合键必须是 realSuffix 而非 contactNo：虚拟号一单一号，按联系号聚合会把同一客户的多件拆散 */
    @Query("""
            select p from Parcel p
            where p.realSuffix = :suffix and p.status = com.sf.station.parcel.domain.ParcelStatus.PENDING
            order by p.inboundAt asc
            """)
    List<Parcel> findPendingBySuffix(@Param("suffix") String suffix);

    Page<Parcel> findByPickupCode(String pickupCode, Pageable pageable);

    Page<Parcel> findByPickupCodeAndStatus(String pickupCode, ParcelStatus status, Pageable pageable);

    Page<Parcel> findByTrackingNo(String trackingNo, Pageable pageable);

    Page<Parcel> findByTrackingNoAndStatus(String trackingNo, ParcelStatus status, Pageable pageable);

    Page<Parcel> findByContactNo(String contactNo, Pageable pageable);

    Page<Parcel> findByContactNoAndStatus(String contactNo, ParcelStatus status, Pageable pageable);

    Page<Parcel> findByStatus(ParcelStatus status, Pageable pageable);

    // =========================================================================
    // 统计
    // =========================================================================

    long countByStatus(ParcelStatus status);

    @Query("select count(p) from Parcel p where p.inboundAt >= :from and p.inboundAt < :to")
    long countInboundBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("select count(p) from Parcel p where p.outboundAt >= :from and p.outboundAt < :to")
    long countOutboundBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 滞留件数：实时计算，不落库（INV-3） */
    @Query("""
            select count(p) from Parcel p
            where p.status = com.sf.station.parcel.domain.ParcelStatus.PENDING and p.inboundAt <= :boundary
            """)
    long countOverdue(@Param("boundary") LocalDateTime boundary);

    @Query("""
            select p.courier, count(p) from Parcel p
            where p.status = com.sf.station.parcel.domain.ParcelStatus.PENDING
            group by p.courier order by count(p) desc
            """)
    List<Object[]> countInStockByCourier();

    /** 该排在库量 */
    @Query("""
            select count(p) from Parcel p
            where p.codePrefix = :prefix and p.status = com.sf.station.parcel.domain.ParcelStatus.PENDING
            """)
    int countInStockByPrefix(@Param("prefix") String prefix);

    /** 该排冷却中数量：已出库、槽位仍占、且冷却未满 */
    @Query("""
            select count(p) from Parcel p
            where p.codePrefix = :prefix and p.codeSlotFlag = 1
              and p.outboundAt is not null and p.outboundAt > :boundary
            """)
    int countCoolingByPrefix(@Param("prefix") String prefix, @Param("boundary") LocalDateTime boundary);

    /** 近 N 天该排的每日入库计数，用于 EWMA */
    @Query("""
            select cast(p.inboundAt as date), count(p) from Parcel p
            where p.codePrefix = :prefix and p.inboundAt >= :from
            group by cast(p.inboundAt as date) order by cast(p.inboundAt as date)
            """)
    List<Object[]> dailyInboundCounts(@Param("prefix") String prefix, @Param("from") LocalDateTime from);

    /** 近 N 天该排的每日出库计数，用于 EWMA */
    @Query("""
            select cast(p.outboundAt as date), count(p) from Parcel p
            where p.codePrefix = :prefix and p.outboundAt >= :from
            group by cast(p.outboundAt as date) order by cast(p.outboundAt as date)
            """)
    List<Object[]> dailyOutboundCounts(@Param("prefix") String prefix, @Param("from") LocalDateTime from);
}
