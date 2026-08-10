package com.sf.station.parcel.api;

import com.sf.station.common.ApiResponse;
import com.sf.station.parcel.api.dto.InboundRequest;
import com.sf.station.parcel.api.dto.ParcelVO;
import com.sf.station.parcel.application.InboundAppService;
import com.sf.station.parcel.application.ParcelAssembler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 包裹接口。
 *
 * <p>分层规则：Controller 只做参数绑定与 DTO 转换，
 * <b>事务注解不允许出现在 Controller</b>。
 */
@RestController
@RequestMapping("/api/v1/parcels")
@Tag(name = "包裹", description = "入库、查询、取件、撤销、催取")
public class ParcelController {

    private final InboundAppService inboundService;
    private final ParcelAssembler assembler;

    public ParcelController(InboundAppService inboundService, ParcelAssembler assembler) {
        this.inboundService = inboundService;
        this.assembler = assembler;
    }

    @PostMapping
    @Operation(summary = "包裹入库", description = "AUTO 模式由系统分配取件码，MANUAL 模式校验员工输入的码")
    public ResponseEntity<ApiResponse<ParcelVO>> inbound(@Valid @RequestBody InboundRequest req) {
        ParcelVO vo = assembler.toVO(inboundService.inbound(req.toCommand()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(vo));
    }
}
