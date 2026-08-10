package com.sf.station.parcel.application;

import com.sf.station.code.application.CodeAllocationService;
import com.sf.station.code.application.CooldownQueryService;
import com.sf.station.code.domain.CodeSpace;
import com.sf.station.code.domain.PickupCodeNormalizer;
import com.sf.station.code.domain.PickupCodeVO;
import com.sf.station.code.domain.Tier;
import com.sf.station.code.repository.CodeSpaceRepository;
import com.sf.station.common.BizException;
import com.sf.station.common.ErrorCode;
import com.sf.station.contact.ContactInfo;
import com.sf.station.contact.ContactResolver;
import com.sf.station.parcel.domain.CodeSource;
import com.sf.station.parcel.domain.EventType;
import com.sf.station.parcel.domain.Parcel;
import com.sf.station.parcel.domain.ParcelStatus;
import com.sf.station.parcel.repository.ParcelRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 入库事务单元（文档 §8.3 内层）。
 *
 * <p><b>这是全系统最容易写错的一段。</b>本类只负责"一次尝试"，
 * 约束冲突异常直接向上冒泡，由 {@link InboundAppService} 在事务外重试。
 *
 * <p>重试循环<b>绝不能</b>写在本类内部：约束冲突异常一旦冒泡出 @Transactional 边界，
 * 事务已被标记 rollback-only，原地重试会抛 UnexpectedRollbackException。
 */
@Service
public class InboundTxService {

    private final ParcelRepository parcelRepo;
    private final CodeSpaceRepository spaceRepo;
    private final CodeAllocationService allocation;
    private final CooldownQueryService cooldownQuery;
    private final ContactResolver contactResolver;
    private final EventRecorder events;
    private final Clock clock;

    public InboundTxService(ParcelRepository parcelRepo, CodeSpaceRepository spaceRepo,
                            CodeAllocationService allocation, CooldownQueryService cooldownQuery,
                            ContactResolver contactResolver, EventRecorder events, Clock clock) {
        this.parcelRepo = parcelRepo;
        this.spaceRepo = spaceRepo;
        this.allocation = allocation;
        this.cooldownQuery = cooldownQuery;
        this.contactResolver = contactResolver;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public Parcel inbound(InboundCommand cmd) {
        LocalDateTime now = LocalDateTime.now(clock);

        ContactInfo contact = contactResolver.resolve(cmd.contactNo(), cmd.manualSuffix());
        CodeSource mode = cmd.codeMode() == null ? CodeSource.AUTO : cmd.codeMode();

        // 1. 选排 + 选号
        SeqPick pick = resolveSeq(cmd, mode, now);
        CodeSpace space = pick.space();
        int seq = pick.seq();
        LocalDateTime boundary = cooldownQuery.boundary(space, now);

        // 2. 按需自愈：冷却已过但 flag 未被回炉任务清理时定向释放，避免撞唯一索引。
        //    与回炉任务构成双保险——任务延迟不阻塞入库，分配疏漏由任务兜底。
        parcelRepo.releaseSlotIfCooled(space.getPrefix(), seq, boundary, now);

        // 3. 落库。INV-2：不做"先查后插"，唯一索引才是真正的防线
        PickupCodeVO code = PickupCodeVO.of(space.getPrefix(), seq);
        Parcel p = Parcel.newInbound(
                cmd.trackingNo().trim(), cmd.courier(),
                contact.contactNo(), contact.contactType(),
                contact.realSuffix(), contact.suffixSource(),
                cmd.receiverName(),
                code.fullCode(), code.prefix(), code.seq(),
                mode, pick.forced(), cmd.operator(), cmd.remark(), now);

        // 必须 saveAndFlush：JPA 默认在事务提交前才 flush，
        // 那时约束冲突异常已在调用方的 catch 之外触发，无法捕获
        parcelRepo.saveAndFlush(p);

        // 4. 游标前进。无需加锁：游标的正确性不影响系统正确性，只影响复用间隔的质量
        space.setCursorPos(seq);
        space.setUpdatedAt(now);
        spaceRepo.save(space);

        // 5. 流水
        String detail = "取件码 " + code.fullCode() + "，来源 " + mode
                + (pick.forced() ? "，EMERGENCY 强制提前复用" : "")
                + (contact.needsSuffixPatch() ? "，虚拟号待补录尾号" : "");
        events.record(p.getId(), EventType.INBOUND, null, ParcelStatus.PENDING,
                cmd.operator(), detail, now);

        if (pick.forced() && pick.victimId() != null) {
            events.record(pick.victimId(), EventType.SLOT_FORCE_REUSE, null, null,
                    cmd.operator(), "码 " + code.fullCode() + " 被提前复用，新包裹 id=" + p.getId(), now);
        }
        return p;
    }

    /** 选排与选号的结果 */
    private record SeqPick(CodeSpace space, int seq, boolean forced, Long victimId) {
    }

    private SeqPick resolveSeq(InboundCommand cmd, CodeSource mode, LocalDateTime now) {
        if (mode == CodeSource.MANUAL) {
            // MANUAL：先归一化再校验。不归一化，唯一索引形同虚设
            PickupCodeVO code = PickupCodeNormalizer.normalize(cmd.pickupCode());
            CodeSpace space = allocation.requireEnabled(code.prefix());
            allocation.assertManualCodeUsable(space, code.seq(), now);
            return new SeqPick(space, code.seq(), false, null);
        }

        // AUTO
        CodeSpace space = allocation.resolveSpace(cmd.scope(), cmd.codePrefix(), now);
        OptionalInt seq = allocation.allocateSeq(space, now);
        if (seq.isPresent()) {
            return new SeqPick(space, seq.getAsInt(), false, null);
        }

        // 码空间耗尽：EMERGENCY 档允许强制复用，否则 P2004
        return forceReuseOrFail(space, now);
    }

    /**
     * 码空间耗尽时的降级路径。
     *
     * <p>EMERGENCY 档下选 outbound_at 最早的已出库码强制复用，置 code_reuse_forced=1，
     * 写 SLOT_FORCE_REUSE 事件。<b>永远不得抢占在库包裹的码。</b>
     */
    private SeqPick forceReuseOrFail(CodeSpace space, LocalDateTime now) {
        if (space.getTier() == Tier.EMERGENCY) {
            Optional<Parcel> victim = allocation.pickForceReuseVictim(space);
            if (victim.isPresent()) {
                Parcel v = victim.get();
                // 释放被抢占者的槽位，腾出唯一索引位置
                v.setCodeSlotFlag(null);
                v.setUpdatedAt(now);
                parcelRepo.saveAndFlush(v);
                return new SeqPick(space, v.getCodeSeq(), true, v.getId());
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("prefix", space.getPrefix());
        payload.put("capacity", space.getCapacity());
        payload.put("alternatives", allocation.alternatives(space, now));
        throw new BizException(ErrorCode.CODE_SPACE_EXHAUSTED,
                "该排码空间已耗尽：" + space.getPrefix(), payload);
    }
}
