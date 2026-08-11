package com.sf.station.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sf.station.code.domain.CooldownMode;
import com.sf.station.support.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class CooldownSettingsApiTest extends BaseIntegrationTest {

    @Test
    @DisplayName("全局冷却参数可读取、保存并成为运行时配置")
    void settingsArePersistentRuntimeConfig() throws Exception {
        mockMvc.perform(get("/api/v1/settings/cooldown"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.minDays").value(3))
                .andExpect(jsonPath("$.data.maxDays").value(90))
                .andExpect(jsonPath("$.data.statWindowDays").value(14));

        mockMvc.perform(put("/api/v1/settings/cooldown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody(2, 60, 5, 12, 0.35, 0.08)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.minDays").value(2))
                .andExpect(jsonPath("$.data.maxDays").value(60))
                .andExpect(jsonPath("$.data.bufferDays").value(5))
                .andExpect(jsonPath("$.data.statWindowDays").value(12))
                .andExpect(jsonPath("$.data.operator").value("站长李"));

        mockMvc.perform(get("/api/v1/settings/cooldown"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tightThreshold").value(0.35));
    }

    @Test
    @DisplayName("最短最长、默认值和档位阈值必须满足组合约束")
    void invalidRelationsAreRejected() throws Exception {
        mockMvc.perform(put("/api/v1/settings/cooldown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody(10, 8, 9, 14, 0.10, 0.20)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("P1001"))
                .andExpect(jsonPath("$.data.minDays").exists())
                .andExpect(jsonPath("$.data.defaultDays").exists())
                .andExpect(jsonPath("$.data.emergencyThreshold").exists());
    }

    @Test
    @DisplayName("新全局边界不能使现有手动货架越界")
    void manualSpaceConflictIsRejected() throws Exception {
        var manual = spaceRepo.findById("15-1").orElseThrow();
        manual.setCooldownMode(CooldownMode.MANUAL);
        manual.setCooldownDays(40);
        spaceRepo.saveAndFlush(manual);

        mockMvc.perform(put("/api/v1/settings/cooldown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody(3, 30, 7, 14, 0.30, 0.10)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("P1001"))
                .andExpect(jsonPath("$.data.conflictingSpaces[0]").value("15-1"));
    }

    private static String validBody(int min, int max, int defaultDays, int window,
                                    double tight, double emergency) {
        return """
                {"minDays":%d,"maxDays":%d,"bufferDays":5,"defaultDays":%d,
                 "tightThreshold":%s,"emergencyThreshold":%s,"ewmaAlpha":0.3,
                 "statWindowDays":%d,"operator":"站长李"}
                """.formatted(min, max, defaultDays, tight, emergency, window);
    }
}
