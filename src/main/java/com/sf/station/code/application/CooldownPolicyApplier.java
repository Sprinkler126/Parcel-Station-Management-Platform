package com.sf.station.code.application;

import com.sf.station.code.domain.CodeSpace;
import com.sf.station.code.domain.CooldownConfig;
import com.sf.station.code.domain.CooldownDecision;
import com.sf.station.code.domain.CooldownMode;
import com.sf.station.code.domain.CooldownPolicy;
import com.sf.station.code.domain.CooldownPolicyLog;
import com.sf.station.code.domain.SpaceMetrics;
import com.sf.station.code.domain.Tier;
import com.sf.station.code.repository.CodeSpaceRepository;
import com.sf.station.code.repository.CooldownPolicyLogRepository;
import com.sf.station.common.BizException;
import com.sf.station.common.ErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 冷却期自适应调节的执行侧（文档 §9.5）。
 *
 * <p><b>本类只做副作用，不做判断</b>：读指标 → 调用纯函数 {@link CooldownPolicy#decide}
 * → 写配置 → 记日志。决策逻辑一行都不在这里，因此
 * 滞回、非对称响应、分档切换全部可以脱离 Spring 上下文做纯单测。
 *
 * <p><b>两个执行时机</b>：
 * <ul>
 *   <li>每日凌晨低峰全量跑一次。冷却期是天级量纲，无需小时级抖动；</li>
 *   <li>分配路径上事件触发：某排可用率跌破 TIGHT 线时立即重算，不等次日。
 *       等一天意味着站员会有一整天面对"没号可用"，这是生产事故。</li>
 * </ul>
 *
 * <p>无论是否变更都写 {@code cooldown_policy_log}，含完整指标快照。
 * 可解释性是这类自动机制的生命线：站员看到 15-1 是 4 天而 15-2 是 30 天，
 * 必须能查到为什么。只记"变更"的日志在排查时最没用——
 * 恰恰是"为什么没变"才是被追问的那个问题。
 */
@Service
public class CooldownPolicyApplier {

    private static final Logger log = LoggerFactory.getLogger(CooldownPolicyApplier.class);

    private final CodeSpaceRepository spaceRepo;
    private final CooldownPolicyLogRepository logRepo;
    private final SpaceMetricsService metricsService;
    private final CooldownQueryService cooldownQuery;
    private final Clock clock;

    /**
     * 内部自调用不走 Spring 代理，@Transactional 会静默失效。
     * {@code applyAll} / {@code applyIfTight} / {@code setCooldown} 都要调 {@code apply}，
     * 因此把事务边界显式化；同时使“某一排决策失败”不会拖垮整轮重算。
     */
    private final TransactionTemplate txTemplate;

    public CooldownPolicyApplier(CodeSpaceRepository spaceRepo, CooldownPolicyLogRepository logRepo,
                                 SpaceMetricsService metricsService,
                                 CooldownQueryService cooldownQuery, Clock clock,
                                 PlatformTransactionManager txManager) {
        this.spaceRepo = spaceRepo;
        this.logRepo = logRepo;
        this.metricsService = metricsService;
        this.cooldownQuery = cooldownQuery;
        this.clock = clock;
        this.txTemplate = new TransactionTemplate(txManager);
        this.txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // =========================================================================
    // 定时与批量
    // =========================================================================

    /** 每日凌晨 3:10 全量重算 */
    @Scheduled(cron = "0 10 3 * * *")
    public void scheduledApply() {
        List<CooldownDecision> ds = applyAll();
        log.info("cooldown policy applied to {} spaces, {} changed",
                ds.size(), ds.stream().filter(CooldownDecision::changed).count());
    }

    public List<CooldownDecision> applyAll() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<CooldownDecision> result = new ArrayList<>();
        for (CodeSpace space : spaceRepo.findAllEnabled()) {
            try {
                result.add(inTx(space.getPrefix(), now));
            } catch (RuntimeException e) {
                log.error("cooldown decision failed for {}, continue", space.getPrefix(), e);
            }
        }
        return result;
    }

    // =========================================================================
    // 单排决策
    // =========================================================================

    public CooldownDecision apply(String prefix) {
        return inTx(prefix, LocalDateTime.now(clock));
    }

    /** 包一层显式事务，供本类内部调用（自调用不走代理） */
    private CooldownDecision inTx(String prefix, LocalDateTime now) {
        return txTemplate.execute(status -> apply(prefix, now));
    }

    /**
     * 对一排执行一次决策。
     *
     * <p>MANUAL 模式不改天数，但<b>仍然计算并记录档位</b>：
     * 档位决定 EMERGENCY 下是否允许强制复用，是保命开关，
     * 不能因为管理员手动锁定了天数就一并停掉。
     */
    @Transactional
    public CooldownDecision apply(String prefix, LocalDateTime now) {
        CodeSpace space = spaceRepo.findById(prefix)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "货架排不存在：" + prefix));
        SpaceMetrics m = metricsService.collect(space, now);
        CooldownConfig cfg = cooldownQuery.config();
        int oldDays = space.getCooldownDays();

        CooldownDecision decision = CooldownPolicy.decide(m, oldDays, cfg);

        if (space.getCooldownMode() == CooldownMode.MANUAL) {
            // 手动锁定天数，但档位照常生效
            decision = new CooldownDecision(oldDays, decision.tier(), false,
                    "手动模式，冷却天数锁定为 " + oldDays + " 天；档位判定仍生效（"
                            + decision.reason() + "）");
        }

        space.setCooldownDays(decision.newDays());
        space.setTier(decision.tier());
        space.setUpdatedAt(now);
        spaceRepo.save(space);

        logRepo.save(CooldownPolicyLog.of(m, oldDays, decision, now));

        if (decision.tier() != Tier.NORMAL) {
            log.warn("space {} entered {} tier: available={}/{} ({}%), reason={}",
                    prefix, decision.tier(), m.available(), m.capacity(),
                    Math.round(m.availableRatio() * 100), decision.reason());
        }
        return decision;
    }

    /**
     * 分配路径上的事件触发（文档 §9.5）。
     *
     * <p>只在<b>确实跌破 TIGHT 线</b>时才重算，避免每次入库都跑一遍指标聚合。
     * 返回是否触发了重算，供调用方记日志。
     */
    @Transactional
    public boolean applyIfTight(CodeSpace space, LocalDateTime now) {
        SpaceMetrics m = metricsService.collect(space, now);
        if (m.availableRatio() >= cooldownQuery.config().tightThreshold()
                && space.getTier() == Tier.NORMAL) {
            return false;
        }
        inTx(space.getPrefix(), now);
        return true;
    }

    // =========================================================================
    // 手动覆盖（文档 §9.5：手动值须过安全校验）
    // =========================================================================

    /**
     * 手动设定固定冷却天数。
     *
     * <p>必须过安全校验：若设定值会让可用率跌破 TIGHT 线，返回 P3001 并给出建议上限。
     * <b>没有这道校验，管理员一个 300 天就能把整排锁死</b>——
     * 而且是慢性锁死，几周后才发作，届时没人会把故障和这次配置改动联系起来。
     *
     * @param days 为 null 表示切回 AUTO 模式
     */
    @Transactional
    public CodeSpace setCooldown(String prefix, Integer days, String operator) {
        LocalDateTime now = LocalDateTime.now(clock);
        CodeSpace space = spaceRepo.findById(prefix)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "货架排不存在：" + prefix));
        CooldownConfig cfg = cooldownQuery.config();

        if (days == null) {
            space.setCooldownMode(CooldownMode.AUTO);
            space.setUpdatedAt(now);
            spaceRepo.save(space);
            inTx(prefix, now);           // 立即按自动策略重算一次
            return spaceRepo.findById(prefix).orElseThrow();
        }

        if (days < cfg.minDays() || days > cfg.maxDays()) {
            throw new BizException(ErrorCode.COOLDOWN_UNSAFE,
                    "冷却天数须在 " + cfg.minDays() + " ~ " + cfg.maxDays() + " 天之间",
                    payload(days, cfg.minDays(), cfg.maxDays()));
        }

        SpaceMetrics m = metricsService.collect(space, now);
        int maxSafe = CooldownPolicy.maxSafeDays(m, cfg);
        if (days > maxSafe) {
            Map<String, Object> payload = payload(days, cfg.minDays(), maxSafe);
            payload.put("capacity", m.capacity());
            payload.put("inStock", m.inStock());
            payload.put("dailyPickup", Math.round(m.dailyPickup() * 100) / 100.0);
            payload.put("reason", "按当前在库 " + m.inStock() + " 件、日均出库 "
                    + Math.round(m.dailyPickup() * 10) / 10.0 + " 件推算，"
                    + days + " 天冷却会使可用率跌破 "
                    + Math.round(cfg.tightThreshold() * 100) + "% 安全线");
            throw new BizException(ErrorCode.COOLDOWN_UNSAFE,
                    "冷却天数 " + days + " 超出安全上限 " + maxSafe + " 天", payload);
        }

        int oldDays = space.getCooldownDays();
        space.setCooldownMode(CooldownMode.MANUAL);
        space.setCooldownDays(days);
        space.setUpdatedAt(now);
        spaceRepo.save(space);

        CooldownDecision d = new CooldownDecision(days, space.getTier(), days != oldDays,
                "管理员 " + (operator == null ? "未知" : operator) + " 手动设定为 " + days
                        + " 天（安全上限 " + maxSafe + " 天）");
        logRepo.save(CooldownPolicyLog.of(m, oldDays, d, now));
        return space;
    }

    private static Map<String, Object> payload(int requested, int minAllowed, int maxAllowed) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requested", requested);
        m.put("minAllowed", minAllowed);
        m.put("maxAllowed", maxAllowed);
        return m;
    }
}
