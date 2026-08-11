package com.sf.station.parcel.application;

import com.sf.station.common.AppProperties;
import com.sf.station.common.BizException;
import com.sf.station.common.ErrorCode;
import com.sf.station.parcel.api.dto.ParcelVO;
import com.sf.station.parcel.domain.OverdueLevel;
import com.sf.station.parcel.domain.Parcel;
import com.sf.station.parcel.domain.ParcelEvent;
import com.sf.station.parcel.domain.ParcelStatus;
import com.sf.station.parcel.repository.ParcelEventRepository;
import com.sf.station.parcel.repository.ParcelRepository;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 分层检索（文档 §2 F2 / §8.5）。
 *
 * <p><b>三条不可让步的规则</b>：
 * <ol>
 *   <li>尾号一律等值匹配走 {@code idx_suffix}，禁止 {@code like '%1234'}；</li>
 *   <li>取件码在比对前必须归一化，否则 {@code 15-1-0731} 查不到 {@code 15-1-731}；</li>
 *   <li>滞留档位过滤在 SQL 侧翻译成 {@code inbound_at} 的时间区间，
 *       <b>不读任何落库的滞留字段</b>（INV-3）。</li>
 * </ol>
 */
@Service
@Transactional(readOnly = true)
public class ParcelQueryService {

    private final ParcelRepository parcelRepo;
    private final ParcelEventRepository eventRepo;
    private final ParcelAssembler assembler;
    private final AppProperties props;
    private final Clock clock;

    public ParcelQueryService(ParcelRepository parcelRepo, ParcelEventRepository eventRepo,
                              ParcelAssembler assembler, AppProperties props, Clock clock) {
        this.parcelRepo = parcelRepo;
        this.eventRepo = eventRepo;
        this.assembler = assembler;
        this.props = props;
        this.clock = clock;
    }

    /** 分页检索 */
    public Page<ParcelVO> search(ParcelQuery q) {
        LocalDateTime now = LocalDateTime.now(clock);
        Page<Parcel> page = parcelRepo.findAll(spec(q, now), PageRequest.of(q.page(), q.size()));
        return page.map(p -> assembler.toVO(p, now));
    }

    public Parcel require(Long id) {
        return parcelRepo.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "包裹不存在：" + id));
    }

    public ParcelVO detail(Long id) {
        return assembler.toVO(require(id));
    }

    /** 状态流水（INV-6：只追加不覆盖，故按时间正序即完整还原了处置过程） */
    public List<ParcelEvent> events(Long id) {
        require(id);
        return eventRepo.findByParcelIdOrderByOccurredAtAscIdAsc(id);
    }

    /**
     * 待取件列表：某真实尾号下的全部在库包裹，供批量取件页确认。
     */
    public List<ParcelVO> pendingBySuffix(String suffix) {
        return assembler.toVOList(parcelRepo.findPendingBySuffix(suffix));
    }

    // =========================================================================
    // Specification 拼装
    // =========================================================================

    private Specification<Parcel> spec(ParcelQuery q, LocalDateTime now) {
        return (root, cq, cb) -> {
            List<Predicate> ps = new ArrayList<>();

            if (q.hasKeyword()) {
                SearchChannel ch = q.effectiveChannel();
                String term = ch.normalizeTerm(q.keyword());
                switch (ch) {
                    // 全部等值匹配，全部命中索引
                    case PICKUP_CODE -> ps.add(cb.equal(root.get("pickupCode"), term));
                    case SUFFIX -> ps.add(cb.equal(root.get("realSuffix"), term));
                    case CONTACT_NO -> ps.add(cb.equal(root.get("contactNo"), term));
                    default -> ps.add(cb.equal(root.get("trackingNo"), term));
                }
            }
            if (q.status() != null) {
                ps.add(cb.equal(root.get("status"), q.status()));
            }
            if (q.codePrefix() != null && !q.codePrefix().isBlank()) {
                ps.add(cb.equal(root.get("codePrefix"), q.codePrefix().trim()));
            }
            if (q.overdue() != null) {
                ps.addAll(overduePredicates(q.overdue(), root, cb, now));
            }

            // 排序：在库优先，其次滞留最久者前置（inbound_at 升序即滞留时长降序）。
            // 终态记录按入库时间倒序，符合\"查历史看最近\"的直觉。
            Order inStockFirst = cb.asc(cb.selectCase()
                    .when(cb.equal(root.get("status"), ParcelStatus.PENDING), 0)
                    .otherwise(1));
            cq.orderBy(inStockFirst, cb.asc(root.get("inboundAt")), cb.asc(root.get("id")));
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    /**
     * 滞留档位 → inbound_at 时间区间。
     *
     * <p>档位是"当前时刻减入库时间"的实时推论，故过滤条件只能是时间区间，
     * 不存在也不允许存在一个可以直接 {@code where overdue_level = 'ALERT'} 的列。
     * 边界含等号，与 {@link ParcelVO} 的展示口径严格一致，
     * 否则会出现"列表标红但按 ALERT 过滤查不到"的自相矛盾。
     */
    private List<Predicate> overduePredicates(OverdueLevel level,
                                              jakarta.persistence.criteria.Root<Parcel> root,
                                              jakarta.persistence.criteria.CriteriaBuilder cb,
                                              LocalDateTime now) {
        LocalDateTime warnAt = now.minusHours(props.getOverdue().getWarnHours());
        LocalDateTime alertAt = now.minusHours(props.getOverdue().getAlertHours());
        List<Predicate> ps = new ArrayList<>();
        // 滞留只对在库包裹有意义
        ps.add(cb.equal(root.get("status"), ParcelStatus.PENDING));
        switch (level) {
            case NORMAL -> ps.add(cb.greaterThan(root.get("inboundAt"), warnAt));
            case WARN -> {
                ps.add(cb.lessThanOrEqualTo(root.get("inboundAt"), warnAt));
                ps.add(cb.greaterThan(root.get("inboundAt"), alertAt));
            }
            case ALERT -> ps.add(cb.lessThanOrEqualTo(root.get("inboundAt"), alertAt));
            default -> {
            }
        }
        return ps;
    }
}
