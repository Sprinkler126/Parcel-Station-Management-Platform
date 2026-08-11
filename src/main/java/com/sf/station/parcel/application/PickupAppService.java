package com.sf.station.parcel.application;

import com.sf.station.common.BizException;
import com.sf.station.common.ErrorCode;
import com.sf.station.contact.ContactResolver;
import com.sf.station.parcel.api.dto.ParcelVO;
import com.sf.station.parcel.api.dto.PickupReceiptVO;
import com.sf.station.parcel.domain.Parcel;
import com.sf.station.parcel.domain.ParcelStatus;
import com.sf.station.parcel.repository.ParcelRepository;
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
 * 出库应用服务（外层，<b>不加 @Transactional</b>）。
 *
 * <p>职责有二，都必须在事务之外完成：
 * <ol>
 *   <li><b>把约束冲突翻译成业务错误码</b>。撤销取件会同时触碰两个唯一索引，
 *       只有在事务回滚之后重新查询，拿到的"当前是谁占着这个码"才是可信的。</li>
 *   <li><b>承载批量的部分成功语义</b>。逐条调用 REQUIRES_NEW 的事务方法，
 *       单条失败只回滚它自己那一层，循环继续。</li>
 * </ol>
 */
@Service
public class PickupAppService {

    private static final Logger log = LoggerFactory.getLogger(PickupAppService.class);

    private final PickupTxService tx;
    private final ParcelRepository parcelRepo;
    private final ParcelAssembler assembler;
    private final ContactResolver contactResolver;

    public PickupAppService(PickupTxService tx, ParcelRepository parcelRepo,
                            ParcelAssembler assembler, ContactResolver contactResolver) {
        this.tx = tx;
        this.parcelRepo = parcelRepo;
        this.assembler = assembler;
        this.contactResolver = contactResolver;
    }

    // =========================================================================
    // 单件
    // =========================================================================

    public PickupReceiptVO pickup(Long id, String operator) {
        Parcel p = tx.pickup(id, operator);
        return new PickupReceiptVO(assembler.toVO(p), p.getOutboundAt(), tx.reusableAt(p));
    }

    public PickupReceiptVO returnParcel(Long id, String operator, String remark) {
        Parcel p = tx.returnParcel(id, operator, remark);
        return new PickupReceiptVO(assembler.toVO(p), p.getOutboundAt(), tx.reusableAt(p));
    }

    /**
     * 撤销取件。两类唯一索引冲突在此翻译。
     *
     * <p>撤销要把 {@code active_flag} 与 {@code code_slot_flag} 双双恢复为 1，
     * 而这段时间里码可能已被复用、同运单号可能已再次入库（TC-09 是合法场景），
     * 因此冲突不是异常情况而是<b>预期分支</b>，必须给出可操作的提示。
     */
    public ParcelVO cancelPickup(Long id, String operator) {
        try {
            return assembler.toVO(tx.cancelPickup(id, operator));
        } catch (DataIntegrityViolationException e) {
            throw translateCancelConflict(id, e);
        }
    }

    public ParcelVO urge(Long id, String operator) {
        return assembler.toVO(tx.urge(id, operator));
    }

    public ParcelVO patchSuffix(Long id, String suffix, String operator) {
        return assembler.toVO(tx.patchSuffix(id, suffix, operator));
    }

    public ParcelVO remark(Long id, String remark, String operator) {
        return assembler.toVO(tx.remark(id, remark, operator));
    }

    // =========================================================================
    // 批量取件（文档 §2 F9）
    // =========================================================================

    /**
     * 按真实后四位聚合批量取件。
     *
     * <p><b>聚合键必须是 {@code real_suffix} 而非 {@code contact_no}</b>。
     * AXB 虚拟号一单一号，按联系号聚合会把同一客户的三个包裹拆成三组，
     * 批量取件功能直接失效——这是隐私面单带来的最隐蔽的一处影响。
     */
    public BatchResult<ParcelVO> pickupBySuffix(String rawSuffix, String operator) {
        String suffix = contactResolver.normalizeSuffix(rawSuffix);
        if (suffix == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "真实后四位应为 4 位数字");
        }
        List<Long> ids = parcelRepo.findPendingBySuffix(suffix).stream().map(Parcel::getId).toList();
        if (ids.isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND,
                    "尾号 " + suffix + " 下没有待取件包裹，可能是隐私单，请尝试取件码或运单号");
        }
        return pickupBatch(ids, operator);
    }

    /**
     * 按 ID 列表批量取件，部分成功。
     *
     * <p>逐条调用 {@code REQUIRES_NEW} 的事务方法：单条失败只回滚它自己那一层，
     * 已成功的不受影响。这正是"其中一件已被家人取走"（TC-14）时的期望行为。
     */
    public BatchResult<ParcelVO> pickupBatch(List<Long> ids, String operator) {
        List<ParcelVO> ok = new ArrayList<>();
        List<BatchResult.Failure> failures = new ArrayList<>();
        for (Long id : ids) {
            try {
                ok.add(assembler.toVO(tx.pickup(id, operator)));
            } catch (BizException e) {
                failures.add(new BatchResult.Failure(id, e.getErrorCode().code(), e.getMessage()));
            } catch (RuntimeException e) {
                log.error("batch pickup failed for id={}", id, e);
                failures.add(new BatchResult.Failure(id, ErrorCode.INTERNAL.code(),
                        ErrorCode.INTERNAL.defaultMessage()));
            }
        }
        return BatchResult.of(ok, failures);
    }

    // =========================================================================
    // 冲突翻译
    // =========================================================================

    private BizException translateCancelConflict(Long id, DataIntegrityViolationException e) {
        Parcel self = parcelRepo.findById(id).orElse(null);
        String msg = rootMessage(e);

        // 事务已回滚，此刻查到的持有者是可信的
        if (self != null) {
            Optional<Parcel> holder = parcelRepo.findSlotHolder(self.getPickupCode())
                    .filter(h -> !h.getId().equals(id));
            if (holder.isPresent()) {
                Parcel h = holder.get();
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("pickupCode", self.getPickupCode());
                payload.put("currentHolderParcelId", h.getId());
                payload.put("currentHolderTrackingNo", h.getTrackingNo());
                payload.put("currentHolderInboundAt", h.getInboundAt());
                return new BizException(ErrorCode.CODE_ALREADY_REUSED,
                        "撤销失败：取件码 " + self.getPickupCode() + " 已被运单 "
                                + h.getTrackingNo() + " 复用，请人工改派新码", payload);
            }

            // 同运单号已有新的未完结记录：TC-09 允许取件后再次入库，此时撤销历史行
            // 会把 activeFlag 恢复为 1 从而撞 uk_tracking_active。
            // 不复用 P2001——语义与处置动作都不同：这里要提示站员先处理新入库的那件。
            Optional<Parcel> active = parcelRepo.findActiveByTrackingNo(self.getTrackingNo())
                    .filter(a -> !a.getId().equals(id));
            if (active.isPresent()) {
                Parcel a = active.get();
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("activeParcelId", a.getId());
                payload.put("trackingNo", a.getTrackingNo());
                payload.put("inboundAt", a.getInboundAt());
                payload.put("pickupCode", a.getPickupCode());
                return new BizException(ErrorCode.TRACKING_ACTIVE_EXISTS,
                        "撤销失败：运单 " + a.getTrackingNo() + " 已有新的未完结记录（取件码 "
                                + a.getPickupCode() + "），请先处理该件", payload);
            }
        }

        log.warn("cancel pickup conflict, unable to diagnose: {}", msg);
        return new BizException(ErrorCode.ILLEGAL_STATUS, "撤销失败，请刷新后重试");
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

    /** 供前端"撤销上一件"使用：只允许撤销仍在库的最近一件 */
    public ParcelVO undoInbound(Long id, String operator) {
        Parcel p = parcelRepo.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "包裹不存在：" + id));
        if (p.getStatus() != ParcelStatus.PENDING) {
            throw new BizException(ErrorCode.ILLEGAL_STATUS,
                    "该包裹已出库，无法撤销入库，请改用拒收退回",
                    Map.of("currentStatus", p.getStatus(), "expected", ParcelStatus.PENDING));
        }
        // 撤销入库以"拒收退回"表达：既清除在库状态，又让码进入冷却，
        // 且历史完整保留。物理删除会破坏 INV-6。
        return assembler.toVO(tx.returnParcel(id, operator, "撤销入库（扫错件）"));
    }
}
