package com.sf.station.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sf.station.parcel.domain.EventType;
import com.sf.station.parcel.domain.Parcel;
import com.sf.station.parcel.domain.ParcelStatus;
import com.sf.station.support.BaseIntegrationTest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** 入库主链路的 MockMvc 用例。 */
class InboundApiTest extends BaseIntegrationTest {

    private Map<String, Object> inboundBody(String trackingNo, String prefix) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("trackingNo", trackingNo);
        m.put("courier", "SF");
        m.put("contactNo", "13812345678");
        m.put("receiverName", "张");
        m.put("codeMode", "AUTO");
        m.put("scope", "ROW");
        m.put("codePrefix", prefix);
        m.put("operator", "站员A");
        return m;
    }

    @Test
    @Tag("showcase")
    @DisplayName("TC-01 扫码入库自动生成码：201 / PENDING / 码已归一化 / 流水新增")
    void tc01_inboundAutoGeneratesCode() throws Exception {
        mockMvc.perform(post("/api/v1/parcels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(inboundBody("SF1234567890", "15-1"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.pickupCode").value("15-1-1"))
                .andExpect(jsonPath("$.data.codePrefix").value("15-1"))
                .andExpect(jsonPath("$.data.codeSeq").value(1))
                .andExpect(jsonPath("$.data.contactMasked").value("138****5678"))
                .andExpect(jsonPath("$.data.realSuffix").value("5678"))
                .andExpect(jsonPath("$.data.overdueLevel").value("NORMAL"));

        List<Parcel> all = parcelRepo.findAll();
        assertThat(all).hasSize(1);
        Parcel p = all.get(0);
        // INV-1：入库时两个生命周期标记同时置 1
        assertThat(p.getActiveFlag()).isEqualTo(1);
        assertThat(p.getCodeSlotFlag()).isEqualTo(1);
        assertThat(p.getStatus()).isEqualTo(ParcelStatus.PENDING);

        // INV-6：写入 INBOUND 流水
        var events = eventRepo.findByParcelIdOrderByOccurredAtAscIdAsc(p.getId());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getEventType()).isEqualTo(EventType.INBOUND);
        assertThat(events.get(0).getToStatus()).isEqualTo(ParcelStatus.PENDING);

        // 游标同步前进
        assertThat(spaceRepo.findById("15-1").orElseThrow().getCursorPos()).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-02 连续入库三件：序号依次前进，游标同步更新")
    void tc02_continuousInbound() throws Exception {
        for (int i = 1; i <= 3; i++) {
            mockMvc.perform(post("/api/v1/parcels")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(inboundBody("SF00" + i, "15-1"))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.pickupCode").value("15-1-" + i));
        }
        assertThat(spaceRepo.findById("15-1").orElseThrow().getCursorPos()).isEqualTo(3);
    }

    @Test
    @Tag("showcase")
    @DisplayName("TC-08 运单号未完结重复入库：409 / P2001，仅一条落库")
    void tc08_duplicateActiveTracking() throws Exception {
        mockMvc.perform(post("/api/v1/parcels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(inboundBody("SF-DUP-001", "15-1"))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/parcels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(inboundBody("SF-DUP-001", "15-1"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("P2001"))
                .andExpect(jsonPath("$.data.existingParcelId").isNumber())
                .andExpect(jsonPath("$.data.inboundAt").exists());

        assertThat(parcelRepo.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("F1 联系号无法识别：400 / P1001")
    void inboundWithUnparsableContact() throws Exception {
        Map<String, Object> body = inboundBody("SF-BAD-CONTACT", "15-1");
        body.put("contactNo", "abcdefg");
        mockMvc.perform(post("/api/v1/parcels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("P1001"));
    }

    @Test
    @DisplayName("F14 AXB 虚拟号入库：VIRTUAL，尾号初始为空并标记待补录")
    void inboundVirtualNumber() throws Exception {
        Map<String, Object> body = inboundBody("SF-AXB-001", "15-1");
        body.put("contactNo", "17012345678,8462");
        mockMvc.perform(post("/api/v1/parcels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.contactType").value("VIRTUAL"))
                .andExpect(jsonPath("$.data.realSuffix").doesNotExist())
                .andExpect(jsonPath("$.data.needsSuffixPatch").value(true));
    }

    @Test
    @DisplayName("F1 MANUAL 模式：归一化后落库，15-1-0731 存为 15-1-731")
    void inboundManualCodeNormalized() throws Exception {
        Map<String, Object> body = inboundBody("SF-MANUAL-001", "15-1");
        body.put("codeMode", "MANUAL");
        body.put("pickupCode", "15-1-0731");
        mockMvc.perform(post("/api/v1/parcels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.pickupCode").value("15-1-731"))
                .andExpect(jsonPath("$.data.codeSeq").value(731))
                .andExpect(jsonPath("$.data.codeSource").value("MANUAL"));
    }

    @Test
    @DisplayName("F1 MANUAL 模式撞在库包裹：409 / P2002，含建议码")
    void inboundManualCodeOccupied() throws Exception {
        Map<String, Object> first = inboundBody("SF-OCC-001", "15-1");
        first.put("codeMode", "MANUAL");
        first.put("pickupCode", "15-1-500");
        mockMvc.perform(post("/api/v1/parcels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(first)))
                .andExpect(status().isCreated());

        Map<String, Object> second = inboundBody("SF-OCC-002", "15-1");
        second.put("codeMode", "MANUAL");
        second.put("pickupCode", "15-1-500");
        mockMvc.perform(post("/api/v1/parcels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(second)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("P2002"))
                .andExpect(jsonPath("$.data.trackingNo").value("SF-OCC-001"))
                .andExpect(jsonPath("$.data.suggestedCode").isString());
    }
}
