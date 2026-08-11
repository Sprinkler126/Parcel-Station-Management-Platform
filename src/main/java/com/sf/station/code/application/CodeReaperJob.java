package com.sf.station.code.application;

import com.sf.station.code.domain.CodeSpace;
import com.sf.station.code.repository.CodeSpaceRepository;
import com.sf.station.parcel.application.EventRecorder;
import com.sf.station.parcel.domain.EventType;
import com.sf.station.parcel.domain.Parcel;
import com.sf.station.parcel.repository.ParcelRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 码槽位回炉任务（文档 §8.7）。
 *
 * <p><b>回炉必须落库，滞留必须不落库</b>——两者判据是同一条：
 * 该状态是否影响他人对资源的获取。
 * <ul>
 *   <li>回炉改变的是"这个码能不能被下一个包裹使用"，是真正的资源归属变更，
 *       必须落到 {@code code_slot_flag} 上，否则唯一索引拦不住复用。</li>
 *   <li>滞留只是一个提醒标签，不改变任何资源归属。落库只会引入
 *       "任务未跑到显示错误""取件后忘记回滚"两类不一致。</li>
 * </ul>
 *
 * <p><b>本任务是双保险的一半，且退化为纯优化项。</b>
 * 分配路径已实现按需自愈（选中号后若发现冷却已过但 flag 未清理，在同一事务内定向释放），
 * 因此任务延迟不会阻塞入库；反过来即使分配路径有疏漏，任务也能兜住。
 * 它的实际作用是缩小位图规模——把陈旧的占用位清掉，让 next-fit 的扫描更快。
 *
 * <p>多实例部署时需要 ShedLock 之类的分布式互斥（见 docs/04-演进方向.md）。
 * 单实例原型下不引入，因为重复执行本身是幂等的：
 * {@code where code_slot_flag = 1} 这一前置条件使第二次执行影响 0 行。
 */
@Component
public class CodeReaperJob {

    private static final Logger log = LoggerFactory.getLogger(CodeReaperJob.class);

    private final ParcelRepository parcelRepo;
    private final CodeSpaceRepository spaceRepo;
    private final CooldownQueryService cooldownQuery;
    private final EventRecorder events;
    private final Clock clock;

    /**
     * 每排一个独立事务。
     *
     * <p>这里刻意<b>不用 @Transactional</b>：{@code reapAll} 循环调用 {@code reap} 是自调用，
     * 不走 Spring 代理，注解会静默失效——批量 update 会因为没有事务而直接抛
     * "No EntityManager with actual transaction available"。
     * 用 TransactionTemplate 把边界写在明处，同时保住"一排失败不影响其余排"的语义：
     * 整个回炉包在一个大事务里的话，某一排的意外异常会让所有排的释放一起回滚。
     */
    private final TransactionTemplate txTemplate;

    public CodeReaperJob(ParcelRepository parcelRepo, CodeSpaceRepository spaceRepo,
                         CooldownQueryService cooldownQuery, EventRecorder events, Clock clock,
                         PlatformTransactionManager txManager) {
        this.parcelRepo = parcelRepo;
        this.spaceRepo = spaceRepo;
        this.cooldownQuery = cooldownQuery;
        this.events = events;
        this.clock = clock;
        this.txTemplate = new TransactionTemplate(txManager);
        this.txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 每小时第 5 分钟执行。避开整点，与其它整点任务错开 */
    @Scheduled(cron = "0 5 * * * *")
    public void scheduledReap() {
        int n = reapAll();
        if (n > 0) {
            log.info("code reaper released {} slots", n);
        }
    }

    /** 全部启用排回炉，返回释放总数。测试与运维接口直接调用此方法 */
    public int reapAll() {
        LocalDateTime now = LocalDateTime.now(clock);
        int total = 0;
        for (CodeSpace space : spaceRepo.findAllEnabled()) {
            try {
                Integer n = txTemplate.execute(status -> reap(space, now));
                total += n == null ? 0 : n;
            } catch (RuntimeException e) {
                log.error("reap failed for {}, continue with next row", space.getPrefix(), e);
            }
        }
        return total;
    }

    /**
     * 回炉一排。
     *
     * <p>先查出待释放的行用于写流水，再执行批量 update。
     * 顺序不能反：批量 update 之后这些行的 {@code code_slot_flag} 已是 NULL，
     * 再查就查不到了，流水会丢失。
     *
     * <p>此处的"先查后改"与 INV-2 禁止的"先查后插"不是一回事：
     * 前者只是为了记录，判定与生效都由带前置条件的 update 独立完成；
     * 后者是把唯一性判定建立在查询结果上，中间存在竞态窗口。
     */
    public int reap(CodeSpace space, LocalDateTime now) {
        LocalDateTime boundary = cooldownQuery.boundary(space, now);
        List<Parcel> cooled = parcelRepo.findCooledSlots(space.getPrefix(), boundary);
        if (cooled.isEmpty()) {
            return 0;
        }
        int n = parcelRepo.bulkReleaseCooled(space.getPrefix(), boundary, now);
        int days = cooldownQuery.effectiveDays(space);
        for (Parcel p : cooled) {
            events.record(p.getId(), EventType.SLOT_RELEASE, null, null, "system",
                    "取件码 " + p.getPickupCode() + " 冷却期满（" + days + " 天，出库于 "
                            + p.getOutboundAt() + "），槽位回炉可复用", now);
        }
        log.debug("reaped {} slots in {}", n, space.getPrefix());
        return n;
    }
}
