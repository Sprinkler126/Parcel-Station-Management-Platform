package com.sf.station.code.application;

import com.sf.station.code.api.dto.CodeSpaceCreateRequest;
import com.sf.station.code.api.dto.CodeSpaceUpdateRequest;
import com.sf.station.code.domain.CodeSpace;
import com.sf.station.code.domain.CooldownConfig;
import com.sf.station.code.domain.CooldownMode;
import com.sf.station.code.repository.CodeSpaceRepository;
import com.sf.station.common.BizException;
import com.sf.station.common.ErrorCode;
import com.sf.station.parcel.repository.ParcelRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 货架排配置写模型：新增、容量调整与启停。 */
@Service
public class CodeSpaceAdminService {

    private final CodeSpaceRepository spaceRepo;
    private final ParcelRepository parcelRepo;
    private final CooldownQueryService cooldownQuery;
    private final Clock clock;

    public CodeSpaceAdminService(CodeSpaceRepository spaceRepo, ParcelRepository parcelRepo,
                                 CooldownQueryService cooldownQuery, Clock clock) {
        this.spaceRepo = spaceRepo;
        this.parcelRepo = parcelRepo;
        this.cooldownQuery = cooldownQuery;
        this.clock = clock;
    }

    @Transactional
    public CodeSpace create(CodeSpaceCreateRequest req) {
        String prefix = req.prefix();
        if (spaceRepo.existsById(prefix)) {
            throw new BizException(ErrorCode.CODE_SPACE_EXISTS, "货架排已存在：" + prefix,
                    Map.of("prefix", prefix));
        }
        CooldownConfig config = cooldownQuery.config();
        CodeSpace space = CodeSpace.of(prefix, req.capacity(), config.defaultDays(),
                LocalDateTime.now(clock));
        if (req.cooldownDays() != null) {
            int days = req.cooldownDays();
            if (days < config.minDays() || days > config.maxDays()) {
                throw new BizException(ErrorCode.COOLDOWN_UNSAFE,
                        "冷却天数须在 " + config.minDays() + " ~ " + config.maxDays() + " 天之间",
                        Map.of("requested", days, "minAllowed", config.minDays(),
                                "maxAllowed", config.maxDays()));
            }
            space.setCooldownMode(CooldownMode.MANUAL);
            space.setCooldownDays(days);
        }
        return spaceRepo.save(space);
    }

    @Transactional
    public CodeSpace update(String prefix, CodeSpaceUpdateRequest req) {
        CodeSpace space = spaceRepo.findById(prefix)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "货架排不存在：" + prefix));
        int maxHeldSeq = parcelRepo.findMaxHeldSeqByPrefix(prefix);
        if (req.capacity() < maxHeldSeq) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("requestedCapacity", req.capacity());
            data.put("minAllowedCapacity", maxHeldSeq);
            throw new BizException(ErrorCode.CODE_SPACE_CONFIG_UNSAFE,
                    "容量不能低于当前仍被占用的最大序号 " + maxHeldSeq, data);
        }
        if (!req.enabled() && space.isEnabled()) {
            long held = parcelRepo.countHeldSlotsByPrefix(prefix);
            if (held > 0) {
                throw new BizException(ErrorCode.CODE_SPACE_CONFIG_UNSAFE,
                        "该排仍有 " + held + " 个在库或冷却槽位，暂不能停用",
                        Map.of("heldSlots", held));
            }
        }
        space.setCapacity(req.capacity());
        if (space.getCursorPos() > req.capacity()) {
            space.setCursorPos(req.capacity());
        }
        space.setEnabled(req.enabled() ? 1 : 0);
        space.setUpdatedAt(LocalDateTime.now(clock));
        return spaceRepo.save(space);
    }
}
