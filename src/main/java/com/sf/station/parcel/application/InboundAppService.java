package com.sf.station.parcel.application;

import com.sf.station.code.application.CodeAllocationService;
import com.sf.station.code.application.CooldownPolicyApplier;
import com.sf.station.code.application.CooldownQueryService;
import com.sf.station.code.domain.CodeSpace;
import com.sf.station.code.domain.PickupCodeNormalizer;
import com.sf.station.code.domain.PickupCodeVO;
import com.sf.station.common.AppProperties;
import com.sf.station.common.BizException;
import com.sf.station.common.ErrorCode;
import com.sf.station.parcel.domain.CodeSource;
import com.sf.station.parcel.domain.Parcel;
import com.sf.station.parcel.repository.ParcelRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 入库应用服务（文档 §8.3 外层）。
 *
 * <p><b>本类不加 @Transactional，且必须与 {@link InboundTxService} 是两个 Bean。</b>
 * <ul>
 *   <li>重试循环不能写在事务方法内部：约束冲突异常一旦冒泡出 @Transactional 边界，
 *       事务已被标记 rollback-only，原地重试会抛 UnexpectedRollbackException。</li>
 *   <li>外层必须调用另一个 Bean：自调用不走 Spring 代理，事务注解直接失效。</li>
 *   <li>重试时重新加载位图，不要把序号简单加一：三个请求同时进来时盲目加一会继续撞。
 *       本实现天然满足——重试即重新进入 txService.inbound()，位图整体重新加载。</li>
 * </ul>
 */
@Service
public class InboundAppService {

    private static final Logger log = LoggerFactory.getLogger(InboundAppService.class);

    private final InboundTxService txService;
    private final ParcelRepository parcelRepo;
    private final CodeAllocationService allocation;
    private final CooldownQueryService cooldownQuery;
    private final CooldownPolicyApplier policyApplier;
    private final AppProperties props;
    private final Clock clock;

    public InboundAppService(InboundTxService txService, ParcelRepository parcelRepo,
                             CodeAllocationService allocation, CooldownQueryService cooldownQuery,
                             CooldownPolicyApplier policyApplier,
                             AppProperties props, Clock clock) {
        this.txService = txService;
        this.parcelRepo = parcelRepo;
        this.allocation = allocation;
        this.cooldownQuery = cooldownQuery;
        this.policyApplier = policyApplier;
        this.props = props;
        this.clock = clock;
    }

    public Parcel inbound(InboundCommand cmd) {
        int maxRetry = Math.max(1, props.getInbound().getMaxRetry());
        for (int i = 0; i < maxRetry; i++) {
            try {
                Parcel p = txService.inbound(cmd, i);
                reactToPressure(p.getCodePrefix());
                return p;
            } catch (DataIntegrityViolationException e) {
                String msg = rootMessage(e);

                // 运单号冲突：重试没有意义，直接转为 P2001
                if (containsIgnoreCase(msg, Parcel.UK_TRACKING_ACTIVE)) {
                    throw trackingDuplicated(cmd);
                }

                // 手动指定码冲突：站员指定了具体的码，换一个号不是它要的语义，直接报冲突
                if (cmd.codeMode() == CodeSource.MANUAL) {
                    throw manualCodeConflict(cmd);
                }

                // AUTO 且是码槽位冲突：并发撞号，重新加载位图再取号
                if (containsIgnoreCase(msg, Parcel.UK_CODE_SLOT) || isUnknownConstraint(msg)) {
                    log.warn("code slot conflict on attempt {}/{}, retry with reloaded bitmap: {}",
                            i + 1, maxRetry, msg);
                    continue;
                }
                throw e;
            }
        }
        throw new BizException(ErrorCode.CODE_SPACE_BUSY, "货位繁忙，请重试");
    }

    /**
     * 批量入库，部分成功语义（文档 §10）。
     *
     * <p>逐条走完整的"事务内分配 + 事务外重试"链路，不做任何合并优化。
     * 合并成一个大事务看起来更快，但一条运单号冲突就会让整批回滚，
     * 而卸货场景下重复扫描是常态，整批回滚等于让站员重扫五十件。
     */
    public BatchResult<Parcel> inboundBatch(List<InboundCommand> cmds) {
        List<Parcel> ok = new ArrayList<>();
        List<BatchResult.Failure> failures = new ArrayList<>();
        for (InboundCommand cmd : cmds) {
            try {
                ok.add(inbound(cmd));
            } catch (BizException e) {
                failures.add(new BatchResult.Failure(cmd.trackingNo(),
                        e.getErrorCode().code(), e.getMessage()));
            } catch (RuntimeException e) {
                log.error("batch inbound failed for {}", cmd.trackingNo(), e);
                failures.add(new BatchResult.Failure(cmd.trackingNo(),
                        ErrorCode.INTERNAL.code(), ErrorCode.INTERNAL.defaultMessage()));
            }
        }
        return BatchResult.of(ok, failures);
    }

    // =========================================================================
    // 事件触发的冷却重算（文档 §9.5）
    // =========================================================================

    /**
     * 入库成功后检查该排是否已跌破 TIGHT 线，是则立即重算冷却策略。
     *
     * <p>为什么不只靠每日定时任务：冷却期是天级量纲，但"码不够用"是分钟级事故。
     * 一个大件促销日下午能把一排从 40% 可用率打到 5%，等到次日 3:10 才收紧冷却期，
     * 意味着站员要面对整整一个下午加一个晚上的"没号可用"。
     *
     * <p><b>刻意放在事务外并吞掉所有异常</b>：策略重算是旁路优化，
     * 它失败绝不能让一次已经成功落库的入库回滚——包裹已经在架上了，
     * 因为一次指标聚合超时就告诉站员"入库失败"，是本末倒置。
     */
    private void reactToPressure(String prefix) {
        try {
            allocation.findSpace(prefix).ifPresent(space ->
                    policyApplier.applyIfTight(space, LocalDateTime.now(clock)));
        } catch (RuntimeException e) {
            log.warn("cooldown recompute skipped for {}: {}", prefix, e.toString());
        }
    }

    // =========================================================================
    // 冲突诊断：把 DataIntegrityViolationException 翻译成对站员有用的信息
    // =========================================================================

    private BizException trackingDuplicated(InboundCommand cmd) {
        Map<String, Object> payload = new LinkedHashMap<>();
        parcelRepo.findActiveByTrackingNo(cmd.trackingNo().trim()).ifPresent(exist -> {
            payload.put("existingParcelId", exist.getId());
            payload.put("inboundAt", exist.getInboundAt());
            payload.put("pickupCode", exist.getPickupCode());
        });
        return new BizException(ErrorCode.TRACKING_DUPLICATED,
                "运单号 " + cmd.trackingNo() + " 已有未完结记录", payload);
    }

    /**
     * 手动指定码冲突。此处再查一次持有者以区分 P2002（在库占用）与 P2003（冷却期内）——
     * 事务内的前置校验可能因并发而漏判，这里补一次诊断。
     */
    private BizException manualCodeConflict(InboundCommand cmd) {
        LocalDateTime now = LocalDateTime.now(clock);
        try {
            PickupCodeVO code = PickupCodeNormalizer.normalize(cmd.pickupCode());
            CodeSpace space = allocation.requireEnabled(code.prefix());
            // 复用同一套诊断逻辑，它会抛出 P2002 或 P2003
            allocation.assertManualCodeUsable(space, code.seq(), now);

            // 诊断不出冲突（例如刚被并发释放），给一个通用占用提示
            Optional<Parcel> holder = parcelRepo.findSlotHolder(code.fullCode());
            Map<String, Object> payload = new LinkedHashMap<>();
            holder.ifPresent(h -> {
                payload.put("trackingNo", h.getTrackingNo());
                payload.put("inboundAt", h.getInboundAt());
            });
            payload.put("suggestedCode", allocation.suggest(space, now));
            return new BizException(ErrorCode.CODE_OCCUPIED,
                    "取件码 " + code.fullCode() + " 已被占用", payload);
        } catch (BizException be) {
            return be;
        }
    }

    private static String rootMessage(Throwable e) {
        StringBuilder sb = new StringBuilder();
        Throwable t = e;
        int depth = 0;
        while (t != null && depth++ < 10) {
            if (t.getMessage() != null) {
                sb.append(t.getMessage()).append(' ');
            }
            t = t.getCause();
        }
        return sb.toString();
    }

    private static boolean containsIgnoreCase(String s, String kw) {
        return s != null && s.toLowerCase().contains(kw.toLowerCase());
    }

    /** 约束名未出现在异常信息里时（不同数据库措辞不同），保守地按可重试处理 */
    private static boolean isUnknownConstraint(String msg) {
        return !containsIgnoreCase(msg, Parcel.UK_TRACKING_ACTIVE)
                && !containsIgnoreCase(msg, Parcel.UK_CODE_SLOT);
    }
}
