package com.sf.station.code.application;

import com.sf.station.code.api.dto.CooldownSettingsRequest;
import com.sf.station.code.api.dto.CooldownSettingsVO;
import com.sf.station.code.domain.CodeSpace;
import com.sf.station.code.domain.CooldownConfig;
import com.sf.station.code.domain.CooldownMode;
import com.sf.station.code.domain.CooldownSettings;
import com.sf.station.code.repository.CodeSpaceRepository;
import com.sf.station.code.repository.CooldownSettingsRepository;
import com.sf.station.common.BizException;
import com.sf.station.common.ErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CooldownSettingsService {

    private final CooldownSettingsRepository settingsRepo;
    private final CodeSpaceRepository spaceRepo;
    private final Clock clock;

    public CooldownSettingsService(CooldownSettingsRepository settingsRepo,
                                   CodeSpaceRepository spaceRepo, Clock clock) {
        this.settingsRepo = settingsRepo;
        this.spaceRepo = spaceRepo;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CooldownConfig config() {
        return current().toConfig();
    }

    @Transactional(readOnly = true)
    public CooldownSettingsVO view() {
        return CooldownSettingsVO.of(current());
    }

    @Transactional
    public CooldownSettingsVO update(CooldownSettingsRequest request) {
        validateRelations(request);
        CooldownConfig config = new CooldownConfig(request.minDays(), request.maxDays(),
                request.bufferDays(), request.defaultDays(), request.tightThreshold(),
                request.emergencyThreshold(), request.ewmaAlpha(), request.statWindowDays());
        rejectManualConflicts(config);
        CooldownSettings settings = current();
        settings.update(config, LocalDateTime.now(clock), normalizeOperator(request.operator()));
        return CooldownSettingsVO.of(settingsRepo.save(settings));
    }

    private CooldownSettings current() {
        return settingsRepo.findById(CooldownSettings.GLOBAL_ID)
                .orElseThrow(() -> new IllegalStateException("全局冷却配置缺失"));
    }

    private void validateRelations(CooldownSettingsRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (request.minDays() > request.maxDays()) {
            errors.put("minDays", "最短天数不能大于最长天数");
        }
        if (request.defaultDays() < request.minDays() || request.defaultDays() > request.maxDays()) {
            errors.put("defaultDays", "新货架默认值必须位于最短和最长天数之间");
        }
        if (request.emergencyThreshold() >= request.tightThreshold()) {
            errors.put("emergencyThreshold", "紧急阈值必须低于紧张阈值");
        }
        if (!errors.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "冷却参数组合无效", errors);
        }
    }

    private void rejectManualConflicts(CooldownConfig config) {
        List<String> conflicts = spaceRepo.findAllByOrderByPrefixAsc().stream()
                .filter(space -> space.getCooldownMode() == CooldownMode.MANUAL)
                .filter(space -> outside(space, config))
                .map(CodeSpace::getPrefix)
                .toList();
        if (!conflicts.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "现有手动冷却值超出新边界，请先调整对应货架",
                    Map.of("conflictingSpaces", conflicts));
        }
    }

    private static boolean outside(CodeSpace space, CooldownConfig config) {
        return space.getCooldownDays() < config.minDays() || space.getCooldownDays() > config.maxDays();
    }

    private static String normalizeOperator(String operator) {
        return operator == null || operator.isBlank() ? null : operator.trim();
    }
}
