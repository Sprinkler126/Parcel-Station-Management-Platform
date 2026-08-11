package com.sf.station.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sf.station.code.application.CodeReaperJob;
import com.sf.station.code.domain.CodeSpace;
import com.sf.station.code.domain.CooldownMode;
import com.sf.station.code.domain.Tier;
import com.sf.station.parcel.domain.EventType;
import com.sf.station.parcel.domain.Parcel;
import com.sf.station.support.BaseIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * 冷却体系的 MockMvc 用例（D4）：TC-04、TC-05、TC-21、TC-22 及回炉、决策日志、P3001。
 *
 * <p>全部通过推进 {@code MutableClock} 制造时间流逝，不使用 Thread.sleep，
 * 因此七天的冷却期在测试里只是一行 {@code clock.advanceDays(7)}。
 */
class CooldownApiTest extends BaseIntegrationTest {

    private static final String OP = "{\"operator\":\"站员B\"}";

    @Autowired
    private CodeReaperJob reaper;

    /** 入库一件后立刻取件，返回其取件码——即"处于冷却期的码" */
    private String inboundThenPickup(String trackingNo, String prefix) throws Exception {
        long id = inbound(trackingNo, prefix);
        String code = reload(id).getPickupCode();
        mockMvc.perform(post("/api/v1/parcels/" + id + "/pickup")
                        .contentType(MediaType.APPLICATION_JSON).content(OP))
                .andExpect(status().isOk());
        return code;
    }

    private Map<String, Object> manualBody(String trackingNo, String code) {
        Map<String, Object> b = inboundBody(trackingNo, "15-1");
        b.put("codeMode", "MANUAL");
        b.put("pickupCode", code);
        return b;
    }

    // =========================================================================
    // TC-04 冷却期内手动指定该码
    // =========================================================================

    @Test
    @Tag("showcase")
    @DisplayName("TC-04 冷却期内手动指定该码：409 / P2003，含 outboundAt、reusableAt、建议码")
    void tc04_manualCodeInCooldown() throws Exception {
        String code = inboundThenPickup("SF-C-001", "15-1");

        // 架上物理已空，但码仍在 7 天冷却内 —— 这正是 P2002 与 P2003 必须分开的原因：
        // 只回"码已占用"，站员看着空货位会认为系统出了故障
        mockMvc.perform(post("/api/v1/parcels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(manualBody("SF-C-002", code))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("P2003"))
                .andExpect(jsonPath("$.data.outboundAt").exists())
                .andExpect(jsonPath("$.data.reusableAt").exists())
                .andExpect(jsonPath("$.data.suggestedCode").exists());
    }

    @Test
    @DisplayName("P2002：码被在库包裹占用时区别于 P2003，且给出在库运单信息")
    void manualCodeOccupiedByInStock() throws Exception {
        long id = inbound("SF-C-010", "15-1");
        String code = reload(id).getPickupCode();

        mockMvc.perform(post("/api/v1/parcels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(manualBody("SF-C-011", code))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("P2002"))
                .andExpect(jsonPath("$.data.trackingNo").value("SF-C-010"))
                .andExpect(jsonPath("$.data.suggestedCode").exists());
    }

    // =========================================================================
    // TC-05 冷却期满后该码再次分配
    // =========================================================================

    @Test
    @DisplayName("TC-05 冷却期满后该码可再次分配：推进时钟 7 天后手动指定成功")
    void tc05_reusableAfterCooldown() throws Exception {
        String code = inboundThenPickup("SF-C-020", "15-1");

        clock.advanceDays(7);

        mockMvc.perform(post("/api/v1/parcels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(manualBody("SF-C-021", code))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.pickupCode").value(code));
    }

    @Test
    @DisplayName("TC-05 边界：差一分钟未满冷却仍拒绝，满整 7 天即放行（闭区间）")
    void tc05_cooldownBoundaryIsClosedInterval() throws Exception {
        String code = inboundThenPickup("SF-C-030", "15-1");

        clock.advanceDays(7);
        clock.advanceMinutes(-1);
        mockMvc.perform(post("/api/v1/parcels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(manualBody("SF-C-031", code))))
                .andExpect(jsonPath("$.code").value("P2003"));

        clock.advanceMinutes(1);
        mockMvc.perform(post("/api/v1/parcels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(manualBody("SF-C-032", code))))
                .andExpect(status().isCreated());
    }

    // =========================================================================
    // TC-21 策略下调后存量立即生效（INV-3）
    // =========================================================================

    @Test
    @Tag("showcase")
    @DisplayName("TC-21 冷却期下调后存量记录立即生效：无需任何回刷即可复用（验证 INV-3）")
    void tc21_policyChangeTakesEffectImmediately() throws Exception {
        String code = inboundThenPickup("SF-C-040", "15-1");

        clock.advanceDays(4);   // 出库已 4 天，7 天冷却下仍不可用
        mockMvc.perform(post("/api/v1/parcels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(manualBody("SF-C-041", code))))
                .andExpect(jsonPath("$.code").value("P2003"));

        // 把冷却期从 7 天下调到 3 天。如果冷却状态是以 reusable_at 落库的，
        // 这里必须扫全表回刷才能生效；本系统只存 outbound_at 原始事实，改配置即刻生效
        mockMvc.perform(put("/api/v1/code-spaces/15-1/cooldown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"days\":3,\"operator\":\"站长李\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cooldownDays").value(3))
                .andExpect(jsonPath("$.data.cooldownMode").value("MANUAL"));

        mockMvc.perform(post("/api/v1/parcels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(manualBody("SF-C-042", code))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.pickupCode").value(code));

        // 反向确认：没有任何一条包裹记录被这次配置变更改写过
        Parcel old = parcelRepo.findAll().stream()
                .filter(p -> "SF-C-040".equals(p.getTrackingNo())).findFirst().orElseThrow();
        assertThat(old.getOutboundAt()).isNotNull();
    }

    // =========================================================================
    // TC-22 码空间耗尽
    // =========================================================================

    @Test
    @DisplayName("TC-22 码空间耗尽：NORMAL 档返回 409 / P2004 并给出替代排")
    void tc22_exhaustedReturnsP2004() throws Exception {
        newSpace("16-1", 2, 7);
        inbound("SF-C-050", "16-1");
        inbound("SF-C-051", "16-1");

        mockMvc.perform(post("/api/v1/parcels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(inboundBody("SF-C-052", "16-1"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("P2004"))
                .andExpect(jsonPath("$.data.prefix").value("16-1"))
                .andExpect(jsonPath("$.data.alternatives").isArray());
    }

    @Test
    @DisplayName("TC-22 EMERGENCY 档：强制复用最早出库的码并打 codeReuseForced 标")
    void tc22_emergencyForceReuse() throws Exception {
        CodeSpace tiny = newSpace("16-2", 2, 7);
        long first = inbound("SF-C-060", "16-2");
        inbound("SF-C-061", "16-2");

        // 取走第一件：码进入冷却，位图仍占满 → 空间耗尽
        mockMvc.perform(post("/api/v1/parcels/" + first + "/pickup")
                        .contentType(MediaType.APPLICATION_JSON).content(OP))
                .andExpect(status().isOk());
        String victimCode = reload(first).getPickupCode();

        tiny.setTier(Tier.EMERGENCY);
        spaceRepo.save(tiny);

        String resp = mockMvc.perform(post("/api/v1/parcels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(inboundBody("SF-C-062", "16-2"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.pickupCode").value(victimCode))
                .andReturn().getResponse().getContentAsString();

        long newId = objectMapper.readTree(resp).path("data").path("id").asLong();
        assertThat(reload(newId).getCodeReuseForced()).isEqualTo(1);
        // 被抢占者留痕，事后可追溯"这个码为什么提前复用了"
        assertThat(eventTypes(first)).contains(EventType.SLOT_FORCE_REUSE);
    }

    @Test
    @DisplayName("EMERGENCY 也绝不抢占在库包裹的码：无已出库候选时仍报 P2004")
    void emergencyNeverPreemptsInStock() throws Exception {
        CodeSpace tiny = newSpace("16-3", 2, 7);
        inbound("SF-C-070", "16-3");
        inbound("SF-C-071", "16-3");
        tiny.setTier(Tier.EMERGENCY);
        spaceRepo.save(tiny);

        mockMvc.perform(post("/api/v1/parcels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(inboundBody("SF-C-072", "16-3"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("P2004"));
    }

    // =========================================================================
    // 回炉任务（F6）
    // =========================================================================

    @Test
    @DisplayName("回炉任务：冷却期满后清空 codeSlotFlag 并写 SLOT_RELEASE，activeFlag 不受影响")
    void reaperReleasesCooledSlots() throws Exception {
        long id = inbound("SF-C-080", "15-1");
        mockMvc.perform(post("/api/v1/parcels/" + id + "/pickup")
                        .contentType(MediaType.APPLICATION_JSON).content(OP))
                .andExpect(status().isOk());

        // 冷却未满：回炉不动它
        assertThat(reaper.reapAll()).isZero();
        assertThat(reload(id).getCodeSlotFlag()).isEqualTo(1);

        clock.advanceDays(7);
        assertThat(reaper.reapAll()).isEqualTo(1);

        Parcel p = reload(id);
        assertThat(p.getCodeSlotFlag()).isNull();     // 槽位释放
        assertThat(p.getActiveFlag()).isNull();       // 订单早已完结，两条生命线互不干扰
        assertThat(eventTypes(id)).contains(EventType.SLOT_RELEASE);

        // 幂等：再跑一次不应重复释放
        assertThat(reaper.reapAll()).isZero();
    }

    @Test
    @DisplayName("回炉任务不碰在库包裹：PENDING 无 outboundAt，永远不进回炉集合")
    void reaperIgnoresInStock() throws Exception {
        long id = inbound("SF-C-090", "15-1");
        clock.advanceDays(30);

        assertThat(reaper.reapAll()).isZero();
        assertThat(reload(id).getCodeSlotFlag()).isEqualTo(1);
    }

    // =========================================================================
    // 手动设定与安全校验（P3001）
    // =========================================================================

    @Test
    @DisplayName("P3001：手动冷却值超安全上限被拒，data 含 maxAllowed 与推算依据")
    void p3001_manualCooldownTooLong() throws Exception {
        // 容量 10、在库 8，可用只剩 2 —— 此时再设 90 天冷却等于把这排锁死
        newSpace("17-1", 10, 7);
        for (int i = 0; i < 8; i++) {
            inbound("SF-C-1" + i, "17-1");
        }

        mockMvc.perform(put("/api/v1/code-spaces/17-1/cooldown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"days\":90,\"operator\":\"站长李\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("P3001"))
                .andExpect(jsonPath("$.data.requested").value(90))
                .andExpect(jsonPath("$.data.maxAllowed").exists())
                .andExpect(jsonPath("$.data.reason").exists());
    }

    @Test
    @DisplayName("手动设定后切回 AUTO：模式复位并立即按自适应策略重算一次")
    void switchBackToAuto() throws Exception {
        mockMvc.perform(put("/api/v1/code-spaces/15-1/cooldown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"days\":5,\"operator\":\"站长李\"}"))
                .andExpect(jsonPath("$.data.cooldownMode").value("MANUAL"));

        mockMvc.perform(put("/api/v1/code-spaces/15-1/cooldown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"站长李\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cooldownMode").value("AUTO"));

        assertThat(spaceRepo.findById("15-1").orElseThrow().getCooldownMode())
                .isEqualTo(CooldownMode.AUTO);
    }

    // =========================================================================
    // 可用性与决策日志
    // =========================================================================

    @Test
    @DisplayName("可用性接口：冷却量与可用数均实时派生，取件后立即体现")
    void availabilityIsDerivedLive() throws Exception {
        newSpace("18-1", 100, 7);
        long id = inbound("SF-C-200", "18-1");

        mockMvc.perform(get("/api/v1/pickup-codes/availability").param("prefix", "18-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].capacity").value(100))
                .andExpect(jsonPath("$.data[0].inStock").value(1))
                .andExpect(jsonPath("$.data[0].cooling").value(0))
                .andExpect(jsonPath("$.data[0].available").value(99))
                .andExpect(jsonPath("$.data[0].nextCode").exists());

        mockMvc.perform(post("/api/v1/parcels/" + id + "/pickup")
                        .contentType(MediaType.APPLICATION_JSON).content(OP))
                .andExpect(status().isOk());

        // 在库 → 冷却，可用数不变：槽位仍被占着，这正是冷却的含义
        mockMvc.perform(get("/api/v1/pickup-codes/availability").param("prefix", "18-1"))
                .andExpect(jsonPath("$.data[0].inStock").value(0))
                .andExpect(jsonPath("$.data[0].cooling").value(1))
                .andExpect(jsonPath("$.data[0].available").value(99));

        clock.advanceDays(7);
        mockMvc.perform(get("/api/v1/pickup-codes/availability").param("prefix", "18-1"))
                .andExpect(jsonPath("$.data[0].cooling").value(0))
                .andExpect(jsonPath("$.data[0].available").value(100));
    }

    @Test
    @DisplayName("决策日志：无论是否变更都留痕，含完整指标快照与理由")
    void policyLogAlwaysRecorded() throws Exception {
        mockMvc.perform(post("/api/v1/code-spaces/15-1/recompute"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reason").exists())
                .andExpect(jsonPath("$.data.tier").exists());

        mockMvc.perform(get("/api/v1/code-spaces/15-1/policy-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].prefix").value("15-1"))
                .andExpect(jsonPath("$.data[0].reason").exists())
                .andExpect(jsonPath("$.data[0].capacity").value(9999))
                .andExpect(jsonPath("$.data[0].oldDays").exists())
                .andExpect(jsonPath("$.data[0].newDays").exists());

        assertThat(policyLogRepo.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("可用率跌破 30% 后自动进入 TIGHT 档并把冷却期压到下限 3 天")
    void tightTierCompressesCooldown() throws Exception {
        newSpace("19-1", 10, 7);
        for (int i = 0; i < 8; i++) {          // 可用率 20% < 30%
            inbound("SF-C-3" + i, "19-1");
        }

        mockMvc.perform(post("/api/v1/code-spaces/19-1/recompute"))
                .andExpect(jsonPath("$.data.tier").value("TIGHT"))
                .andExpect(jsonPath("$.data.newDays").value(3));

        assertThat(spaceRepo.findById("19-1").orElseThrow().getTier()).isEqualTo(Tier.TIGHT);
    }

    @Test
    @DisplayName("入库路径事件触发：压力上来时无需等次日定时任务，当场重算档位")
    void inboundTriggersRecomputeWhenTight() throws Exception {
        newSpace("19-2", 10, 7);
        for (int i = 0; i < 8; i++) {
            inbound("SF-C-4" + i, "19-2");
        }

        // 没有手动调过任何接口，档位已由入库链路顺带更新
        assertThat(spaceRepo.findById("19-2").orElseThrow().getTier()).isNotEqualTo(Tier.NORMAL);
        assertThat(policyLogRepo.count()).isPositive();
    }
}
