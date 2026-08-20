package com.sf.station.parcel.api;

import com.sf.station.common.ApiResponse;
import com.sf.station.parcel.api.dto.BatchPickupRequest;
import com.sf.station.parcel.api.dto.InboundRequest;
import com.sf.station.parcel.api.dto.OperationRequest;
import com.sf.station.parcel.api.dto.PageVO;
import com.sf.station.parcel.api.dto.ParcelEventVO;
import com.sf.station.parcel.api.dto.ParcelVO;
import com.sf.station.parcel.api.dto.PickupReceiptVO;
import com.sf.station.parcel.api.dto.SuffixPatchRequest;
import com.sf.station.parcel.application.BatchResult;
import com.sf.station.parcel.application.InboundAppService;
import com.sf.station.parcel.application.InboundCommand;
import com.sf.station.parcel.application.ParcelAssembler;
import com.sf.station.parcel.application.ParcelQuery;
import com.sf.station.parcel.application.ParcelQueryService;
import com.sf.station.parcel.application.PickupAppService;
import com.sf.station.parcel.application.SearchChannel;
import com.sf.station.parcel.domain.OverdueLevel;
import com.sf.station.parcel.domain.ParcelStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 包裹接口。
 *
 * <p>分层规则：Controller 只做参数绑定与 DTO 转换，
 * <b>事务注解不允许出现在 Controller</b>（文档 §13 禁止事项）。
 */
@RestController
@RequestMapping("/api/v1/parcels")
@Tag(name = "包裹", description = "入库、查询、取件、批量取件、撤销、退回、催取、补录尾号、状态流水")
public class ParcelController {

    private final InboundAppService inboundService;
    private final PickupAppService pickupService;
    private final ParcelQueryService queryService;
    private final ParcelAssembler assembler;

    public ParcelController(InboundAppService inboundService, PickupAppService pickupService,
                            ParcelQueryService queryService, ParcelAssembler assembler) {
        this.inboundService = inboundService;
        this.pickupService = pickupService;
        this.queryService = queryService;
        this.assembler = assembler;
    }

    // =========================================================================
    // 入库
    // =========================================================================

    @PostMapping
    @Operation(summary = "包裹入库", description = "AUTO 模式由系统分配取件码，MANUAL 模式校验员工输入的码")
    public ResponseEntity<ApiResponse<ParcelVO>> inbound(@Valid @RequestBody InboundRequest req) {
        ParcelVO vo = assembler.toVO(inboundService.inbound(req.toCommand()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(vo));
    }

    @PostMapping("/batch")
    @Operation(summary = "批量入库", description = "部分成功语义，单条失败不影响其余条目")
    public ApiResponse<BatchResult<ParcelVO>> inboundBatch(
            @Valid @RequestBody List<InboundRequest> reqs) {
        List<InboundCommand> cmds = reqs.stream().map(InboundRequest::toCommand).toList();
        BatchResult<com.sf.station.parcel.domain.Parcel> r = inboundService.inboundBatch(cmds);
        return ApiResponse.ok(BatchResult.of(assembler.toVOList(r.success()), r.failures()));
    }

    // =========================================================================
    // 查询
    // =========================================================================

    @GetMapping
    @Operation(summary = "分层检索",
            description = "keyword 形态自动判断通道：形如 n-n-n 走取件码，4 位数字走真实尾号，"
                    + "11 位手机号走联系号，其余走运单号。查无结果返回空列表而非报错")
    public ApiResponse<PageVO<ParcelVO>> search(
            @Parameter(description = "查询串") @RequestParam(required = false) String keyword,
            @Parameter(description = "强制指定检索通道，默认 AUTO")
            @RequestParam(required = false) SearchChannel channel,
            @Parameter(description = "收件人姓名，支持包含匹配")
            @RequestParam(required = false) String receiverName,
            @Parameter(description = "完整取件码，精确匹配")
            @RequestParam(required = false) String pickupCode,
            @Parameter(description = "真实手机后四位，精确匹配")
            @RequestParam(required = false) String realSuffix,
            @Parameter(description = "运单号，精确匹配")
            @RequestParam(required = false) String trackingNo,
            @Parameter(description = "完整联系号，精确匹配")
            @RequestParam(required = false) String contactNo,
            @Parameter(description = "状态过滤") @RequestParam(required = false) ParcelStatus status,
            @Parameter(description = "滞留档位过滤") @RequestParam(required = false) OverdueLevel overdue,
            @Parameter(description = "排前缀过滤，如 15-1")
            @RequestParam(required = false) String codePrefix,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        ParcelQuery q = new ParcelQuery(keyword, channel, receiverName, pickupCode, realSuffix,
                trackingNo, contactNo, status, overdue, codePrefix, page, size);
        return ApiResponse.ok(PageVO.of(queryService.search(q)));
    }

    @GetMapping("/{id}/pickup-companions")
    @Operation(summary = "同客户待取包裹",
            description = "返回与指定包裹收件人姓名及真实手机尾号均相同的其他在库包裹")
    public ApiResponse<List<ParcelVO>> pickupCompanions(@PathVariable Long id) {
        return ApiResponse.ok(queryService.pendingCompanions(id));
    }

    @GetMapping("/{id}")
    @Operation(summary = "包裹详情")
    public ApiResponse<ParcelVO> detail(@PathVariable Long id) {
        return ApiResponse.ok(queryService.detail(id));
    }

    @GetMapping("/{id}/events")
    @Operation(summary = "状态流水", description = "只追加不覆盖，撤销以反向事件表达（INV-6）")
    public ApiResponse<List<ParcelEventVO>> events(@PathVariable Long id) {
        return ApiResponse.ok(queryService.events(id).stream().map(ParcelEventVO::of).toList());
    }

    // =========================================================================
    // 出库与状态流转
    // =========================================================================

    @PostMapping("/{id}/pickup")
    @Operation(summary = "确认取件",
            description = "activeFlag 置 NULL 但 codeSlotFlag 保持为 1 进入冷却（INV-1）。"
                    + "重复取件返回 P2005 而非静默成功")
    public ApiResponse<PickupReceiptVO> pickup(@PathVariable Long id,
                                               @Valid @RequestBody(required = false) OperationRequest req) {
        return ApiResponse.ok(pickupService.pickup(id, operator(req), req == null ? null : req.getRequestId()));
    }

    @PostMapping("/pickup-batch")
    @Operation(summary = "批量取件",
            description = "聚合键为 realSuffix 而非 contactNo——虚拟号一单一号，"
                    + "按联系号聚合会把同一客户的多件拆散。部分成功语义")
    public ApiResponse<BatchResult<ParcelVO>> pickupBatch(@Valid @RequestBody BatchPickupRequest req) {
        BatchResult<ParcelVO> r = req.getIds() != null && !req.getIds().isEmpty()
                ? pickupService.pickupBatch(req.getIds(), req.getOperator(), req.getRequestId())
                : pickupService.pickupBySuffix(req.getRealSuffix(), req.getOperator(), req.getRequestId());
        return ApiResponse.ok(r);
    }

    @PostMapping("/{id}/cancel-pickup")
    @Operation(summary = "撤销取件",
            description = "回到 PENDING 并重新占用码槽位。码已被复用返回 P2006，"
                    + "同运单号已有新未完结记录返回 P2008")
    public ApiResponse<ParcelVO> cancelPickup(@PathVariable Long id,
                                              @Valid @RequestBody(required = false) OperationRequest req) {
        return ApiResponse.ok(pickupService.cancelPickup(id, operator(req)));
    }

    @PostMapping("/{id}/return")
    @Operation(summary = "拒收退回", description = "码槽位处理与取件一致，同样进入冷却期")
    public ApiResponse<PickupReceiptVO> returnParcel(@PathVariable Long id,
                                                     @Valid @RequestBody(required = false) OperationRequest req) {
        String remark = req == null ? null : req.getRemark();
        return ApiResponse.ok(pickupService.returnParcel(id, operator(req), remark));
    }

    @PostMapping("/{id}/undo-inbound")
    @Operation(summary = "撤销入库",
            description = "可撤销任一仍在库的入库记录；以拒收退回表达而非物理删除，保全流水完整性")
    public ApiResponse<ParcelVO> undoInbound(@PathVariable Long id,
                                             @Valid @RequestBody(required = false) OperationRequest req) {
        return ApiResponse.ok(pickupService.undoInbound(id, operator(req)));
    }

    @PostMapping("/{id}/urge")
    @Operation(summary = "记录催取", description = "真实驿站超期要收费或退回，催取记录是依据")
    public ApiResponse<ParcelVO> urge(@PathVariable Long id,
                                      @Valid @RequestBody(required = false) OperationRequest req) {
        return ApiResponse.ok(pickupService.urge(id, operator(req)));
    }

    @PostMapping("/{id}/remark")
    @Operation(summary = "异常件备注")
    public ApiResponse<ParcelVO> remark(@PathVariable Long id,
                                        @Valid @RequestBody OperationRequest req) {
        return ApiResponse.ok(pickupService.remark(id, req.getRemark(), operator(req)));
    }

    @PatchMapping("/{id}/suffix")
    @Operation(summary = "补录真实尾号",
            description = "AXB 虚拟号入库时尾号未知，补录后方可按尾号检索与批量取件")
    public ApiResponse<ParcelVO> patchSuffix(@PathVariable Long id,
                                             @Valid @RequestBody SuffixPatchRequest req) {
        return ApiResponse.ok(pickupService.patchSuffix(id, req.getRealSuffix(), req.getOperator()));
    }

    private static String operator(OperationRequest req) {
        return req == null ? null : req.getOperator();
    }
}
