package com.sf.station.parcel.application;

import com.sf.station.code.application.CodeAllocationService;
import com.sf.station.code.application.CooldownQueryService;
import com.sf.station.code.domain.CodeSpace;
import com.sf.station.common.BizException;
import com.sf.station.common.ErrorCode;
import com.sf.station.contact.ContactResolver;
import com.sf.station.parcel.domain.EventType;
import com.sf.station.parcel.domain.Parcel;
import com.sf.station.parcel.domain.ParcelStatus;
import com.sf.station.parcel.repository.ParcelRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 出库与状态流转事务单元（文档 §8.6，INV-5）。
 *
 * <p><b>每个方法都是一次独立的原子尝试</b>，约束冲突直接向上冒泡，
 * 由 {@link PickupAppService} 在事务外翻译成业务错误码。
 * 这与入库同构：事务边界内不做异常翻译，因为此时事务已被标记 rollback-only，
 * 任何补充查询都拿不到可信结果。
 *
 * <p><b>INV-5</b>：所有状态流转一律 {@code update ... where id = ? and status = ?}，
 * 以受影响行数判定成败，绝不"先查状态再更新"——那中间是一个真实存在的竞态窗口，
 * 两个站员同时点"确认取件"会双双成功，取件事故就此产生。
 *
 * <p>批量取件的每一件都走 {@link Propagation#REQUIRES_NEW}，
 * 这是"部分失败不整体回滚"语义的落点：单件失败只回滚它自己那一层。
 */
@Service
public class PickupTxService {

    private final ParcelRepository parcelRepo;
    private final EventRecorder events;
    private final ContactResolver contactResolver;
    private final CodeAllocationService allocation;
    private final CooldownQueryService cooldownQuery;
    private final Clock clock;

    public PickupTxService(ParcelRepository parcelRepo, EventRecorder events,
                           ContactResolver contactResolver, CodeAllocationService allocation,
                           CooldownQueryService cooldownQuery, Clock clock) {
        this.parcelRepo = parcelRepo;
        this.events = events;
        this.contactResolver = contactResolver;
        this.allocation = allocation;
        this.cooldownQuery = cooldownQuery;
        this.clock = clock;
    }

    // =========================================================================
    // 确认取件
    // =========================================================================

    /**
     * 确认取件。
     *
     * <p><b>INV-1 在此分岔</b>：{@code activeFlag} 置 NULL 释放运单唯一槽位
     * （使同运单号可以再次入库），但 {@code codeSlotFlag} 保持为 1 进入冷却期
     * （使该取件码短期内不被复用）。用一个字段同时表达两者会直接导致冷却失效。
     *
     * <p><b>重复取件不做静默幂等成功</b>："已被他人取走"是必须让站员立刻知道的信息，
     * 静默成功会掩盖取件事故。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Parcel pickup(Long id, String operator, String requestId) {
        LocalDateTime now = LocalDateTime.now(clock);
        int n = parcelRepo.markPickedUp(id, now, operator, requestId);
        if (n == 0) {
            Parcel current = require(id);
            if (requestId != null && requestId.equals(current.getPickupRequestId())
                    && current.getStatus() == ParcelStatus.PICKED_UP) {
                return current;
            }
            throw notPending(current, "确认取件");
        }
        events.record(id, EventType.PICKUP, ParcelStatus.PENDING, ParcelStatus.PICKED_UP,
                operator, "确认取件，码进入冷却期", now);
        return reload(id);
    }

    /**
     * 拒收退回。
     *
     * <p>码槽位处理与取件完全一致——同样写 {@code outbound_at}、同样进入冷却。
     * 理由是客户手里的旧取件通知同样存在，复用该码同样会导致取错件；
     * 退回与取件在"码这个资源"的视角下没有区别（文档未定义，实施阶段补齐，见 README）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Parcel returnParcel(Long id, String operator, String remark) {
        LocalDateTime now = LocalDateTime.now(clock);
        int n = parcelRepo.markReturned(id, now, operator, remark);
        if (n == 0) {
            throw notPending(id, "拒收退回");
        }
        events.record(id, EventType.RETURN, ParcelStatus.PENDING, ParcelStatus.RETURNED,
                operator, "拒收退回：" + (remark == null ? "" : remark), now);
        return reload(id);
    }

    // =========================================================================
    // 撤销取件
    // =========================================================================

    /**
     * 撤销取件：回到 PENDING，重新占用码槽位、恢复 activeFlag、清空出库时间。
     *
     * <p>这条 update 会同时触碰<b>两个</b>唯一索引，因此有两种冲突可能，
     * 都由 {@link PickupAppService} 在事务外翻译：
     * <ul>
     *   <li>{@code uk_code_slot}：该码已被新包裹复用 → P2006，需人工改派新码；</li>
     *   <li>{@code uk_tracking_active}：同运单号已有新的未完结记录 → P2008。</li>
     * </ul>
     *
     * <p><b>撤销不覆盖历史</b>：{@code outbound_at} 虽被清空，但 PICKUP 与
     * CANCEL_PICKUP 两条事件完整保留了"曾于某时出库、某时撤销、操作人是谁"（INV-6）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Parcel cancelPickup(Long id, String operator) {
        LocalDateTime now = LocalDateTime.now(clock);
        Parcel before = require(id);
        LocalDateTime pickedAt = before.getOutboundAt();

        int n = parcelRepo.markCancelPickup(id, now, operator);
        if (n == 0) {
            Parcel p = require(id);
            if (p.getStatus() == ParcelStatus.PENDING) {
                throw new BizException(ErrorCode.ILLEGAL_STATUS, "该包裹当前在库，无需撤销",
                        Map.of("currentStatus", p.getStatus(), "expected", ParcelStatus.PICKED_UP));
            }
            throw new BizException(ErrorCode.ILLEGAL_STATUS,
                    "只有已取件的包裹可以撤销，当前状态：" + p.getStatus(),
                    Map.of("currentStatus", p.getStatus(), "expected", ParcelStatus.PICKED_UP));
        }
        events.record(id, EventType.CANCEL_PICKUP, ParcelStatus.PICKED_UP, ParcelStatus.PENDING,
                operator, "撤销取件，原出库时间 " + pickedAt + "，码槽位重新占用", now);
        return reload(id);
    }

    // =========================================================================
    // 催取 / 补录 / 备注
    // =========================================================================

    /** 催取。真实驿站超期要收费或退回，催取记录是依据。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Parcel urge(Long id, String operator) {
        LocalDateTime now = LocalDateTime.now(clock);
        int n = parcelRepo.markUrged(id, now);
        if (n == 0) {
            throw notPending(id, "催取");
        }
        Parcel p = reload(id);
        events.record(id, EventType.URGE, ParcelStatus.PENDING, ParcelStatus.PENDING,
                operator, "第 " + p.getUrgeCount() + " 次催取", now);
        return p;
    }

    /**
     * 补录真实尾号。
     *
     * <p>AXB 虚拟号一单一号、签收后回收复用，无法作为客户身份标识，入库时
     * {@code realSuffix} 只能为空，检索通道随之缺失。补录入口嵌在"查询无结果"处，
     * 使数据补全发生在真实作业动线中，而非单独做一个管理页面。
     *
     * <p>补录允许对任意状态执行：站员可能在包裹已取走后才回头补数据。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Parcel patchSuffix(Long id, String rawSuffix, String operator) {
        LocalDateTime now = LocalDateTime.now(clock);
        Parcel before = require(id);
        String suffix = contactResolver.normalizeSuffix(rawSuffix);
        if (suffix == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "真实后四位不能为空");
        }
        parcelRepo.patchSuffix(id, suffix, now);
        events.record(id, EventType.SUFFIX_PATCH, before.getStatus(), before.getStatus(),
                operator, "补录真实后四位 " + suffix
                        + "（原值 " + (before.getRealSuffix() == null ? "空" : before.getRealSuffix()) + "）",
                now);
        return reload(id);
    }

    /** 异常件备注。不改变状态，但仍写流水——所有人为处置都要可追溯（INV-6）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Parcel remark(Long id, String remark, String operator) {
        LocalDateTime now = LocalDateTime.now(clock);
        Parcel before = require(id);
        parcelRepo.patchRemark(id, remark, now);
        events.record(id, EventType.REMARK, before.getStatus(), before.getStatus(),
                operator, "标记异常件：" + remark, now);
        return reload(id);
    }

    // =========================================================================
    // 辅助
    // =========================================================================

    /** 该码预计回炉日期，供取件回执展示"此码 X 日后方可复用" */
    @Transactional(readOnly = true)
    public LocalDateTime reusableAt(Parcel p) {
        if (p.getOutboundAt() == null) {
            return null;
        }
        Optional<CodeSpace> space = allocation.findSpace(p.getCodePrefix());
        return space.map(s -> cooldownQuery.reusableAt(s, p.getOutboundAt())).orElse(null);
    }

    /**
     * 受影响行数为 0 时再查当前状态，区分 P2005（已取件）与 P2007（其他非法流转）。
     *
     * <p>这一步查询发生在<b>更新之后</b>，不是"先查后改"——判定依据始终是
     * update 的受影响行数，此处的查询只用于生成对站员有用的提示信息。
     */
    private BizException notPending(Long id, String action) {
        return notPending(require(id), action);
    }

    /** CAS 未命中后的状态分类只使用回查得到的最新实体，避免重复查询或误用旧实体。 */
    private BizException notPending(Parcel p, String action) {
        if (p.getStatus() == ParcelStatus.PICKED_UP) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("outboundAt", p.getOutboundAt());
            payload.put("operator", p.getOperator());
            payload.put("pickupCode", p.getPickupCode());
            return new BizException(ErrorCode.ALREADY_PICKED_UP,
                    "该包裹已于 " + p.getOutboundAt() + " 被取走，操作人 "
                            + (p.getOperator() == null ? "未知" : p.getOperator()), payload);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("currentStatus", p.getStatus());
        payload.put("expected", ParcelStatus.PENDING);
        return new BizException(ErrorCode.ILLEGAL_STATUS,
                action + "失败，当前状态为 " + p.getStatus(), payload);
    }

    private Parcel require(Long id) {
        return parcelRepo.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "包裹不存在：" + id));
    }

    /** 批量 update 后实体已从持久化上下文清除，需重新读取才能拿到最新值 */
    private Parcel reload(Long id) {
        return require(id);
    }
}
