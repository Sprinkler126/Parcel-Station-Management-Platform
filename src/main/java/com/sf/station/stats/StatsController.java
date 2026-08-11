package com.sf.station.stats;

import com.sf.station.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 看板统计接口。 */
@RestController
@RequestMapping(value = "/api/v1/stats", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "看板", description = "今日进出、在库、滞留、按排可用性")
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping("/today")
    @Operation(summary = "今日看板",
            description = "所有数字实时聚合，不读任何冗余计数字段（INV-3）")
    public ApiResponse<TodayStatsVO> today() {
        return ApiResponse.ok(statsService.today());
    }
}
