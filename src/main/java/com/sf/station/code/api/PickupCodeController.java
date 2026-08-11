package com.sf.station.code.api;

import com.sf.station.code.api.dto.SpaceAvailabilityVO;
import com.sf.station.code.application.CodeAllocationService;
import com.sf.station.code.application.CodeSpaceQueryService;
import com.sf.station.code.domain.AllocScope;
import com.sf.station.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 取件码相关的只读接口。 */
@RestController
@RequestMapping("/api/v1/pickup-codes")
@Tag(name = "取件码", description = "下一码预览、按排可用性")
public class PickupCodeController {

    private final CodeAllocationService allocation;
    private final CodeSpaceQueryService spaceQuery;
    private final Clock clock;

    public PickupCodeController(CodeAllocationService allocation,
                                CodeSpaceQueryService spaceQuery, Clock clock) {
        this.allocation = allocation;
        this.spaceQuery = spaceQuery;
        this.clock = clock;
    }

    /**
     * 预览下一个可用码。
     *
     * <p><b>不具备占位效力</b>，仅用于连续入库页显示"下一个是 15-1-7232"。
     * 真正的分配必须在入库事务内完成——做成"前端取号后带号提交"会把
     * 时间窗从毫秒级人为放大到秒级（站员扫完码、核对、点提交），重码不可避免。
     */
    @GetMapping("/preview")
    @Operation(summary = "预览下一个可用码",
            description = "仅供展示，不占位。真正的分配在入库事务内完成")
    public ApiResponse<Map<String, Object>> preview(
            @Parameter(description = "取码范围 ROW | SHELF | FULL")
            @RequestParam(defaultValue = "ROW") AllocScope scope,
            @Parameter(description = "ROW 填 15-1；SHELF 填 15；FULL 可空")
            @RequestParam(required = false) String codePrefix) {
        LocalDateTime now = LocalDateTime.now(clock);
        return ApiResponse.ok(allocation.preview(scope, codePrefix, now)
                .map(c -> Map.<String, Object>of(
                        "nextCode", c.fullCode(), "prefix", c.prefix(), "seq", c.seq(),
                        "exhausted", false, "note", "预览值不占位，实际码以入库返回为准"))
                .orElseGet(() -> Map.of("nextCode", "", "exhausted", true,
                        "note", "该范围码空间已耗尽")));
    }

    @GetMapping("/availability")
    @Operation(summary = "按排返回容量 / 在库 / 冷却 / 可用 / 冷却天数 / 档位",
            description = "冷却量与可用数均为实时派生，不读落库字段（INV-3）")
    public ApiResponse<List<SpaceAvailabilityVO>> availability(
            @Parameter(description = "只看某一排") @RequestParam(required = false) String prefix) {
        return ApiResponse.ok(prefix == null || prefix.isBlank()
                ? spaceQuery.availability()
                : List.of(spaceQuery.availability(prefix)));
    }
}
