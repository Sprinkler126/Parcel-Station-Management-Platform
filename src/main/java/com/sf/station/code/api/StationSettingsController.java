package com.sf.station.code.api;

import com.sf.station.code.api.dto.CooldownSettingsRequest;
import com.sf.station.code.api.dto.CooldownSettingsVO;
import com.sf.station.code.application.CooldownPolicyApplier;
import com.sf.station.code.application.CooldownSettingsService;
import com.sf.station.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1/settings", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "站点设置", description = "全站运行参数")
public class StationSettingsController {

    private final CooldownSettingsService settings;
    private final CooldownPolicyApplier applier;

    public StationSettingsController(CooldownSettingsService settings, CooldownPolicyApplier applier) {
        this.settings = settings;
        this.applier = applier;
    }

    @GetMapping("/cooldown")
    @Operation(summary = "读取全局冷却参数")
    public ApiResponse<CooldownSettingsVO> cooldown() {
        return ApiResponse.ok(settings.view());
    }

    @PutMapping(value = "/cooldown", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "更新全局冷却参数并重算全部自动货架")
    public ApiResponse<CooldownSettingsVO> updateCooldown(
            @Valid @RequestBody CooldownSettingsRequest request) {
        CooldownSettingsVO result = settings.update(request);
        applier.applyAll();
        return ApiResponse.ok(result);
    }
}
