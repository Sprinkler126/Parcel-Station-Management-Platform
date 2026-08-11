package com.sf.station.code.application;

import com.sf.station.code.api.dto.CooldownPolicyLogVO;
import com.sf.station.code.api.dto.SpaceAvailabilityVO;
import com.sf.station.code.domain.CodeSpace;
import com.sf.station.code.domain.SpaceMetrics;
import com.sf.station.code.repository.CodeSpaceRepository;
import com.sf.station.code.repository.CooldownPolicyLogRepository;
import com.sf.station.common.BizException;
import com.sf.station.common.ErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.OptionalInt;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 码空间的只读查询：可用性快照与决策日志。 */
@Service
@Transactional(readOnly = true)
public class CodeSpaceQueryService {

    private final CodeSpaceRepository spaceRepo;
    private final CooldownPolicyLogRepository logRepo;
    private final SpaceMetricsService metricsService;
    private final CodeAllocationService allocation;
    private final Clock clock;

    public CodeSpaceQueryService(CodeSpaceRepository spaceRepo, CooldownPolicyLogRepository logRepo,
                                 SpaceMetricsService metricsService,
                                 CodeAllocationService allocation, Clock clock) {
        this.spaceRepo = spaceRepo;
        this.logRepo = logRepo;
        this.metricsService = metricsService;
        this.allocation = allocation;
        this.clock = clock;
    }

    /** 全部启用排的可用性 */
    public List<SpaceAvailabilityVO> availability() {
        LocalDateTime now = LocalDateTime.now(clock);
        return spaceRepo.findAllEnabled().stream().map(s -> toVO(s, now)).toList();
    }

    /** 设置页使用：包含已停用排。 */
    public List<SpaceAvailabilityVO> allAvailability() {
        LocalDateTime now = LocalDateTime.now(clock);
        return spaceRepo.findAllByOrderByPrefixAsc().stream().map(s -> toVO(s, now)).toList();
    }

    public SpaceAvailabilityVO availability(String prefix) {
        CodeSpace space = spaceRepo.findById(prefix)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "货架排不存在：" + prefix));
        return toVO(space, LocalDateTime.now(clock));
    }

    private SpaceAvailabilityVO toVO(CodeSpace space, LocalDateTime now) {
        SpaceMetrics m = metricsService.collect(space, now);
        OptionalInt seq = space.isEnabled() ? allocation.allocateSeq(space, now) : OptionalInt.empty();
        String nextCode = seq.isPresent() ? space.getPrefix() + "-" + seq.getAsInt() : null;
        return SpaceAvailabilityVO.of(space, m, nextCode);
    }

    public List<CooldownPolicyLogVO> policyLogs(String prefix, int limit) {
        int n = limit <= 0 ? 50 : Math.min(limit, 500);
        return logRepo.findByPrefixOrderByDecidedAtDescIdDesc(prefix, PageRequest.of(0, n))
                .stream().map(CooldownPolicyLogVO::of).toList();
    }
}
