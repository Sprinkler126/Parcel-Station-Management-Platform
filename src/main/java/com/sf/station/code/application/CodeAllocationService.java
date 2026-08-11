package com.sf.station.code.application;

import com.sf.station.code.domain.AllocScope;
import com.sf.station.code.domain.CodeAllocator;
import com.sf.station.code.domain.CodeSpace;
import com.sf.station.code.domain.PickupCodeVO;
import com.sf.station.code.repository.CodeSpaceRepository;
import com.sf.station.common.BizException;
import com.sf.station.common.ErrorCode;
import com.sf.station.parcel.domain.Parcel;
import com.sf.station.parcel.repository.ParcelRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * 码分配服务：把纯函数 {@link CodeAllocator} 与仓储、时钟粘合起来。
 *
 * <p>本类只负责"选出一个可用序号"，不负责落库。真正的占位由入库事务内的
 * insert 撞唯一索引来保证——查询只用于给出友好提示（INV-2）。
 */
@Service
public class CodeAllocationService {

    /** 重试时游标抖动的最大跨度，用于把并发线程在码空间上散开 */
    private static final int JITTER_SPAN = 64;

    private final ParcelRepository parcelRepo;
    private final CodeSpaceRepository spaceRepo;
    private final CooldownQueryService cooldownQuery;

    public CodeAllocationService(ParcelRepository parcelRepo, CodeSpaceRepository spaceRepo,
                                 CooldownQueryService cooldownQuery) {
        this.parcelRepo = parcelRepo;
        this.spaceRepo = spaceRepo;
        this.cooldownQuery = cooldownQuery;
    }

    // =========================================================================
    // 排的选定
    // =========================================================================

    /** 按 scope 选定一排。ROW 直接取，SHELF / FULL 挑占用率最低的一排。 */
    public CodeSpace resolveSpace(AllocScope scope, String codePrefix, LocalDateTime now) {
        AllocScope s = scope == null ? AllocScope.ROW : scope;
        return switch (s) {
            case ROW -> requireEnabled(codePrefix);
            case SHELF -> pickLeastOccupied(shelfRows(codePrefix), now);
            case FULL -> pickLeastOccupied(spaceRepo.findAllEnabled(), now);
        };
    }

    /** 按前缀查排，不要求启用。用于展示类场景（如回炉日期换算），排停用了历史数据仍要能读 */
    public Optional<CodeSpace> findSpace(String prefix) {
        return prefix == null ? Optional.empty() : spaceRepo.findById(prefix);
    }

    public CodeSpace requireEnabled(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "codePrefix 不能为空");
        }
        return spaceRepo.findEnabled(prefix)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND,
                        "货架排不存在或已停用：" + prefix));
    }

    private List<CodeSpace> shelfRows(String shelf) {
        if (shelf == null || shelf.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "SHELF 范围需提供货架号");
        }
        // 允许传 "15" 或 "15-1"（后者取其货架号）
        String s = shelf.contains("-") ? shelf.substring(0, shelf.indexOf('-')) : shelf;
        List<CodeSpace> rows = spaceRepo.findEnabledByShelf(s);
        if (rows.isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "该货架下没有启用的排：" + s);
        }
        return rows;
    }

    /** 挑占用率最低的一排，使各排负载均衡 */
    private CodeSpace pickLeastOccupied(List<CodeSpace> spaces, LocalDateTime now) {
        if (spaces == null || spaces.isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "没有可用的货架排");
        }
        return spaces.stream()
                .min(Comparator.comparingDouble(sp -> occupancyRatio(sp, now)))
                .orElseThrow();
    }

    private double occupancyRatio(CodeSpace space, LocalDateTime now) {
        int occupied = occupiedSeqs(space, now).size();
        return space.getCapacity() == 0 ? 1.0 : (double) occupied / space.getCapacity();
    }

    // =========================================================================
    // 序号的选定
    // =========================================================================

    /** 该排真正不可用的序号（在库 + 冷却未满），INV-3 派生判定 */
    public List<Integer> occupiedSeqs(CodeSpace space, LocalDateTime now) {
        return parcelRepo.findOccupiedSeqs(space.getPrefix(), cooldownQuery.boundary(space, now));
    }

    /**
     * AUTO 模式取号：next-fit。
     *
     * @return 空表示该排码空间耗尽
     */
    public OptionalInt allocateSeq(CodeSpace space, LocalDateTime now) {
        return allocateSeq(space, now, 0);
    }

    /**
     * AUTO 模式取号，带重试轮次。
     *
     * <p><b>为什么重试要加游标抖动</b>：并发线程各自加载到的是同一份位图，
     * next-fit 会让它们算出<b>同一个</b>序号，于是全部撞在唯一索引上；
     * 下一轮重新加载位图后又会再次算出同一个新序号，冲突以锁步方式持续，
     * 重试次数很快耗尽（实测 8 线程时必然失败）。
     *
     * <p>因此重试轮次 &gt; 0 时给游标叠加一个随机偏移，把并发线程在码空间上散开。
     * 这<b>不是</b>被禁止的"序号简单加一"——位图仍然整体重新加载，
     * 抖动只改变 next-fit 的搜索起点，不假设某个具体序号可用。
     */
    public OptionalInt allocateSeq(CodeSpace space, LocalDateTime now, int attempt) {
        List<Integer> occupied = occupiedSeqs(space, now);
        int cursor = space.getCursorPos();
        if (attempt > 0) {
            int span = Math.min(space.getCapacity(), JITTER_SPAN);
            cursor += ThreadLocalRandom.current().nextInt(span);
        }
        return CodeAllocator.nextFit(space.getCapacity(), cursor, occupied);
    }

    /** 批量取号，一次加载位图取 N 个 */
    public List<Integer> allocateSeqBatch(CodeSpace space, int n, LocalDateTime now) {
        List<Integer> occupied = occupiedSeqs(space, now);
        return CodeAllocator.nextFitBatch(space.getCapacity(), space.getCursorPos(), occupied, n);
    }

    /**
     * 预览下一个可用码。<b>不具备占位效力</b>，仅用于前端显示"下一个是 15-1-7232"。
     *
     * <p>真正的分配必须在入库事务内完成；做成"前端取号后带号提交"会把时间窗
     * 人为放大到秒级，重码不可避免。
     */
    public Optional<PickupCodeVO> preview(AllocScope scope, String codePrefix, LocalDateTime now) {
        CodeSpace space = resolveSpace(scope, codePrefix, now);
        OptionalInt seq = allocateSeq(space, now);
        return seq.isPresent()
                ? Optional.of(PickupCodeVO.of(space.getPrefix(), seq.getAsInt()))
                : Optional.empty();
    }

    // =========================================================================
    // 冲突诊断：P2002 与 P2003 必须区分
    // =========================================================================

    /**
     * 手动指定码时的可用性校验。
     *
     * <p>站员看到"该码 3 天前已出库、冷却至 X 日"才能理解为什么架上空着却不让用，
     * 只回"码已占用"会直接引发投诉。故必须区分：
     * <ul>
     *   <li>持有者在库（outboundAt 为 null）→ P2002 码被占用</li>
     *   <li>持有者已出库但冷却未满 → P2003 冷却期内，附 reusableAt</li>
     * </ul>
     */
    public void assertManualCodeUsable(CodeSpace space, int seq, LocalDateTime now) {
        String fullCode = space.getPrefix() + "-" + seq;
        if (seq < 1 || seq > space.getCapacity()) {
            throw new BizException(ErrorCode.CODE_FORMAT_INVALID,
                    "排内序号超出该排容量 1~" + space.getCapacity(),
                    Map.of("input", fullCode, "expectedPattern",
                            space.getPrefix() + "-[1~" + space.getCapacity() + "]"));
        }
        Optional<Parcel> holder = parcelRepo.findSlotHolder(fullCode);
        if (holder.isEmpty()) {
            return;
        }
        Parcel h = holder.get();
        LocalDateTime boundary = cooldownQuery.boundary(space, now);

        if (h.getOutboundAt() == null) {
            // 在库占用
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("trackingNo", h.getTrackingNo());
            payload.put("inboundAt", h.getInboundAt());
            payload.put("suggestedCode", suggest(space, now));
            throw new BizException(ErrorCode.CODE_OCCUPIED,
                    "取件码 " + fullCode + " 正被在库包裹占用", payload);
        }
        if (h.getOutboundAt().isAfter(boundary)) {
            // 冷却未满
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("outboundAt", h.getOutboundAt());
            payload.put("reusableAt", cooldownQuery.reusableAt(space, h.getOutboundAt()));
            payload.put("cooldownDays", cooldownQuery.effectiveDays(space));
            payload.put("suggestedCode", suggest(space, now));
            throw new BizException(ErrorCode.CODE_COOLING,
                    "取件码 " + fullCode + " 处于冷却期", payload);
        }
        // 冷却已过但 flag 未清理，交由入库事务的按需自愈处理
    }

    /** 冲突时给出的建议码，支持前端"一键采纳" */
    public String suggest(CodeSpace space, LocalDateTime now) {
        OptionalInt seq = allocateSeq(space, now);
        return seq.isPresent() ? space.getPrefix() + "-" + seq.getAsInt() : null;
    }

    /**
     * 码空间耗尽时返回可用的替代排列表（P2004 的 alternatives 载荷）。
     */
    public List<Map<String, Object>> alternatives(CodeSpace exhausted, LocalDateTime now) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (CodeSpace sp : spaceRepo.findAllEnabled()) {
            if (sp.getPrefix().equals(exhausted.getPrefix())) {
                continue;
            }
            OptionalInt seq = allocateSeq(sp, now);
            if (seq.isPresent()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("prefix", sp.getPrefix());
                m.put("nextCode", sp.getPrefix() + "-" + seq.getAsInt());
                m.put("available", sp.getCapacity() - occupiedSeqs(sp, now).size());
                list.add(m);
            }
            if (list.size() >= 5) {
                break;
            }
        }
        return list;
    }

    /**
     * EMERGENCY 档降级：选 outbound_at 最早的已出库码强制复用。
     *
     * <p><b>永远不得抢占在库包裹的码</b>——仓储查询已强制 outboundAt is not null。
     * 业务连续性优先于冷却保证：宁可复用一个只冷却了两小时的码，
     * 也不能让站员卡在页面上无法入库。
     */
    public Optional<Parcel> pickForceReuseVictim(CodeSpace space) {
        List<Parcel> candidates = parcelRepo.findForceReuseCandidates(
                space.getPrefix(), PageRequest.of(0, 1));
        return candidates.isEmpty() ? Optional.empty() : Optional.of(candidates.get(0));
    }
}
