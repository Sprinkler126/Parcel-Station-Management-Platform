package com.sf.station.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sf.station.support.BaseIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class CodeSpaceSettingsApiTest extends BaseIntegrationTest {

    @Test
    @DisplayName("站点设置：新增 18-1 后可按货架 18 自动分配")
    void createSpaceMakesShelfAvailableForInbound() throws Exception {
        mockMvc.perform(post("/api/v1/code-spaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"shelfNo":"18","rowNo":"1","capacity":500,
                                 "cooldownDays":5,"operator":"站长李"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.prefix").value("18-1"))
                .andExpect(jsonPath("$.data.capacity").value(500))
                .andExpect(jsonPath("$.data.cooldownMode").value("MANUAL"))
                .andExpect(jsonPath("$.data.cooldownDays").value(5));

        mockMvc.perform(get("/api/v1/pickup-codes/preview")
                        .param("scope", "SHELF").param("codePrefix", "18"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nextCode").value("18-1-1"));
    }

    @Test
    @DisplayName("站点设置：重复前缀返回 P3002，全部列表包含停用排")
    void duplicateRejectedAndAllIncludesDisabled() throws Exception {
        mockMvc.perform(post("/api/v1/code-spaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shelfNo\":\"15\",\"rowNo\":\"1\",\"capacity\":9999}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("P3002"));

        var disabled = newSpace("18-1", 100, 7);
        disabled.setEnabled(0);
        spaceRepo.saveAndFlush(disabled);
        mockMvc.perform(get("/api/v1/code-spaces/all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[2].prefix").value("18-1"))
                .andExpect(jsonPath("$.data[2].enabled").value(false));
    }

    @Test
    @DisplayName("站点设置：不能缩容到仍被占用的最大序号以下")
    void capacityCannotExcludeHeldSlot() throws Exception {
        Map<String, Object> body = inboundBody("SF-SETTINGS-001", "15-1");
        body.put("codeMode", "MANUAL");
        body.put("pickupCode", "15-1-500");
        inbound(body);

        mockMvc.perform(put("/api/v1/code-spaces/15-1/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"capacity\":499,\"enabled\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("P3003"))
                .andExpect(jsonPath("$.data.minAllowedCapacity").value(500));
    }

    @Test
    @DisplayName("站点设置：有在库槽位时不能停用，空排可以停用")
    void disableRequiresEmptySpace() throws Exception {
        inbound("SF-SETTINGS-002", "15-1");
        mockMvc.perform(put("/api/v1/code-spaces/15-1/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"capacity\":9999,\"enabled\":false}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("P3003"));

        newSpace("18-1", 100, 7);
        mockMvc.perform(put("/api/v1/code-spaces/18-1/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"capacity\":80,\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.capacity").value(80))
                .andExpect(jsonPath("$.data.enabled").value(false));
    }
}
