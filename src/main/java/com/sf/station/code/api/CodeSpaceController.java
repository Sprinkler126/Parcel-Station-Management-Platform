package com.sf.station.code.api;

import com.sf.station.code.api.dto.CooldownPolicyLogVO;
import com.sf.station.code.api.dto.CooldownSettingRequest;
import com.sf.station.code.api.dto.SpaceAvailabilityVO;
import com.sf.station.code.application.CodeSpaceQueryService;
import com.sf.station.code.application.CooldownPolicyApplier;
import com.sf.station.code.domain.CooldownDecision;
import com.sf.station.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 货架排配置与冷却策略（文档 §10）。 */
@RestController
@RequestMapping(value = "/api/v1/code-spaces", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "码空间", description = "排的可用性、冷却天数设定与决策日志")
public class CodeSpaceController {

    private final CodeSpaceQueryService query;
    private final CooldownPolicyApplier applier;

    public CodeSpaceController(CodeSpaceQueryService query, CooldownPolicyApplier applier) {
        this.query = query;
        this.applier = applier;
    }

    @GetMapping
    @Operation(summary = "列出所有启用排的可用性")
    public ApiResponse<List<SpaceAvailabilityVO>> list() {
        return ApiResponse.ok(query.availability());
    }

    @GetMapping("/{prefix}")
    @Operation(summary = "单排可用性")
    public ApiResponse<SpaceAvailabilityVO> one(@PathVariable String prefix) {
        return ApiResponse.ok(query.availability(prefix));
    }

    /**
     * 手动设定冷却天数，或留空切回自适应。
     *
     * <p>设定值须过安全校验，超上限返回 P3001 并在 data 中给出建议上限与推算依据。
     */
    @PutMapping(value = "/{prefix}/cooldown", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "设定冷却天数 / 切回自适应",
            description = "days 留空表示 AUTO；超安全上限返回 P3001，data 含 maxAllowed 与推算依据")
    public ApiResponse<SpaceAvailabilityVO> setCooldown(
            @PathVariable String prefix,
            @Valid @RequestBody CooldownSettingRequest req) {
        applier.setCooldown(prefix, req.days(), req.operator());
        return ApiResponse.ok(query.availability(prefix));
    }

    /**
     * 立即触发一次策略重算。
     *
     * <p>演示与运维用途：正常由每日 3:10 定时任务与分配路径事件触发。
     */
    @PostMapping("/{prefix}/recompute")
    @Operation(summary = "立即重算该排冷却策略")
    public ApiResponse<Map<String, Object>> recompute(
            @PathVariable String prefix) {
        CooldownDecision d = applier.apply(prefix);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("newDays", d.newDays());
        body.put("tier", d.tier());
        body.put("changed", d.changed());
        body.put("reason", d.reason());
        body.put("availability", query.availability(prefix));
        return ApiResponse.ok(body);
    }

    @GetMapping("/{prefix}/policy-logs")
    @Operation(summary = "冷却决策日志",
            description = "含完整指标快照与决策理由，无论是否变更都会留痕")
    public ApiResponse<List<CooldownPolicyLogVO>> policyLogs(
            @PathVariable String prefix,
            @Parameter(description = "返回条数，默认 50，上限 500")
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(query.policyLogs(prefix, limit));
    }
}
