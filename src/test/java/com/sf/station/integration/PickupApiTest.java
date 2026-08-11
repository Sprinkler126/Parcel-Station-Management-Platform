package com.sf.station.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sf.station.parcel.domain.EventType;
import com.sf.station.parcel.domain.Parcel;
import com.sf.station.parcel.domain.ParcelStatus;
import com.sf.station.support.BaseIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** 取件、撤销、退回、催取的 MockMvc 用例（D3）。 */
class PickupApiTest extends BaseIntegrationTest {

    private static final String OP = "{\"operator\":\"站员B\"}";

    // =========================================================================
    // TC-11 / TC-12 确认取件
    // =========================================================================

    @Test
    @Tag("showcase")
    @DisplayName("TC-11 确认取件：转 PICKED_UP，activeFlag 空，codeSlotFlag 仍为 1")
    void tc11_pickup() throws Exception {
        long id = inbound("SF-PICK-001", "15-1");

        mockMvc.perform(post("/api/v1/parcels/{id}/pickup", id)
                        .contentType(MediaType.APPLICATION_JSON).content(OP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.parcel.status").value("PICKED_UP"))
                .andExpect(jsonPath("$.data.outboundAt").exists())
                // 回执给出该码预计回炉日期（冷却 7 天）
                .andExpect(jsonPath("$.data.codeReusableAt").value("2026-03-09T09:00:00"))
                // 运单生命周期已终结，但码槽位仍被本行占用
                .andExpect(jsonPath("$.data.parcel.slotHeld").value(true));

        Parcel p = reload(id);
        // INV-1 的核心断言：两个生命周期在此分岔
        assertThat(p.getStatus()).isEqualTo(ParcelStatus.PICKED_UP);
        assertThat(p.getActiveFlag()).isNull();          // 运单已完结
        assertThat(p.getCodeSlotFlag()).isEqualTo(1);    // 码进入冷却，槽位不释放
        assertThat(p.getOutboundAt()).isEqualTo(now());
        assertThat(p.getOperator()).isEqualTo("站员B");

        assertThat(eventTypes(id)).containsExactly(EventType.INBOUND, EventType.PICKUP);
        assertThat(eventRepo.findByParcelIdOrderByOccurredAtAscIdAsc(id).get(1).getOccurredAt())
                .isEqualTo(p.getOutboundAt());
    }

    @Test
    @Tag("showcase")
    @DisplayName("TC-12 重复确认取件：409 / P2005，携带上次取件时间与操作人")
    void tc12_duplicatePickup() throws Exception {
        long id = inbound("SF-PICK-002", "15-1");
        mockMvc.perform(post("/api/v1/parcels/{id}/pickup", id)
                .contentType(MediaType.APPLICATION_JSON).content(OP)).andExpect(status().isOk());

        clock.advanceHours(2);

        // 不做静默幂等成功：\"已被他人取走\"必须让站员立刻知道
        mockMvc.perform(post("/api/v1/parcels/{id}/pickup", id)
                        .contentType(MediaType.APPLICATION_JSON).content(OP))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("P2005"))
                .andExpect(jsonPath("$.data.outboundAt").value("2026-03-02T09:00:00"))
                .andExpect(jsonPath("$.data.operator").value("站员B"));

        // 第二次尝试不得产生任何副作用
        assertThat(eventTypes(id)).containsExactly(EventType.INBOUND, EventType.PICKUP);
        assertThat(reload(id).getOutboundAt()).isEqualTo(now().minusHours(2));
    }

    @Test
    @DisplayName("CAS 未命中且包裹已退回：409 / P2007，返回当前状态")
    void pickupReturnedParcelReportsIllegalStatus() throws Exception {
        long id = inbound("SF-PICK-RETURNED", "15-1");
        mockMvc.perform(post("/api/v1/parcels/{id}/return", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"站员B\",\"remark\":\"客户拒收\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/parcels/{id}/pickup", id)
                        .contentType(MediaType.APPLICATION_JSON).content(OP))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("P2007"))
                .andExpect(jsonPath("$.data.currentStatus").value("RETURNED"))
                .andExpect(jsonPath("$.data.expected").value("PENDING"));

        assertThat(eventTypes(id)).containsExactly(EventType.INBOUND, EventType.RETURN);
    }

    // =========================================================================
    // TC-15 撤销取件
    // =========================================================================

    @Test
    @DisplayName("TC-15 撤销取件：回 PENDING，槽位重占，流水保留 PICKUP 与 CANCEL_PICKUP 两条")
    void tc15_cancelPickup() throws Exception {
        long id = inbound("SF-CANCEL-001", "15-1");
        mockMvc.perform(post("/api/v1/parcels/{id}/pickup", id)
                .contentType(MediaType.APPLICATION_JSON).content(OP)).andExpect(status().isOk());

        clock.advanceMinutes(10);

        mockMvc.perform(post("/api/v1/parcels/{id}/cancel-pickup", id)
                        .contentType(MediaType.APPLICATION_JSON).content(OP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.outboundAt").doesNotExist());

        Parcel p = reload(id);
        assertThat(p.getStatus()).isEqualTo(ParcelStatus.PENDING);
        assertThat(p.getActiveFlag()).isEqualTo(1);      // 运单唯一槽位重新占用
        assertThat(p.getCodeSlotFlag()).isEqualTo(1);
        assertThat(p.getOutboundAt()).isNull();

        // INV-6：撤销不是\"改回去\"，而是追加反向事件，历史完整保留
        assertThat(eventTypes(id)).containsExactly(
                EventType.INBOUND, EventType.PICKUP, EventType.CANCEL_PICKUP);
        var cancel = eventRepo.findByParcelIdOrderByOccurredAtAscIdAsc(id).get(2);
        assertThat(cancel.getDetail()).contains("原出库时间 2026-03-02T09:00");
        assertThat(cancel.getFromStatus()).isEqualTo(ParcelStatus.PICKED_UP);
        assertThat(cancel.getToStatus()).isEqualTo(ParcelStatus.PENDING);
    }

    @Test
    @DisplayName("F10 撤销时该码已被复用：409 / P2006，提示人工改派")
    void cancelPickup_codeAlreadyReused() throws Exception {
        // 手动占 15-1-500 后取走，使该码出库
        Map<String, Object> body = inboundBody("SF-REUSE-A", "15-1");
        body.put("codeMode", "MANUAL");
        body.put("pickupCode", "15-1-500");
        long first = inbound(body);
        mockMvc.perform(post("/api/v1/parcels/{id}/pickup", first)
                .contentType(MediaType.APPLICATION_JSON).content(OP)).andExpect(status().isOk());

        // 冷却期满后该码被新包裹复用
        clock.advanceDays(8);
        Map<String, Object> body2 = inboundBody("SF-REUSE-B", "15-1");
        body2.put("codeMode", "MANUAL");
        body2.put("pickupCode", "15-1-500");
        long second = inbound(body2);

        mockMvc.perform(post("/api/v1/parcels/{id}/cancel-pickup", first)
                        .contentType(MediaType.APPLICATION_JSON).content(OP))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("P2006"))
                .andExpect(jsonPath("$.data.currentHolderTrackingNo").value("SF-REUSE-B"))
                .andExpect(jsonPath("$.data.currentHolderParcelId").value(second));

        // 撤销失败，原记录保持终态
        assertThat(reload(first).getStatus()).isEqualTo(ParcelStatus.PICKED_UP);
    }

    @Test
    @DisplayName("P2008 撤销时同运单号已有新的未完结记录：409，提示先处理新入库的那件")
    void cancelPickup_trackingActiveExists() throws Exception {
        // 文档 §2 F10 未覆盖此分支：TC-09 允许取件后同运单号再次入库，
        // 此时对历史行撤销会把 activeFlag 恢复为 1 从而撞 uk_tracking_active
        long first = inbound("SF-REINBOUND-001", "15-1");
        mockMvc.perform(post("/api/v1/parcels/{id}/pickup", first)
                .contentType(MediaType.APPLICATION_JSON).content(OP)).andExpect(status().isOk());

        long second = inbound("SF-REINBOUND-001", "15-1");

        mockMvc.perform(post("/api/v1/parcels/{id}/cancel-pickup", first)
                        .contentType(MediaType.APPLICATION_JSON).content(OP))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("P2008"))
                .andExpect(jsonPath("$.data.activeParcelId").value(second));

        assertThat(reload(first).getStatus()).isEqualTo(ParcelStatus.PICKED_UP);
        assertThat(reload(second).getStatus()).isEqualTo(ParcelStatus.PENDING);
    }

    @Test
    @DisplayName("F10 撤销一个在库包裹：409 / P2007")
    void cancelPickup_notPickedUp() throws Exception {
        long id = inbound("SF-CANCEL-002", "15-1");
        mockMvc.perform(post("/api/v1/parcels/{id}/cancel-pickup", id)
                        .contentType(MediaType.APPLICATION_JSON).content(OP))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("P2007"))
                .andExpect(jsonPath("$.data.currentStatus").value("PENDING"));
    }

    // =========================================================================
    // TC-09 取件后同运单号再次入库
    // =========================================================================

    @Test
    @DisplayName("TC-09 取件后同运单号再次入库：201，历史记录保留")
    void tc09_reInboundAfterPickup() throws Exception {
        long first = inbound("SF-REDELIVER-001", "15-1");
        mockMvc.perform(post("/api/v1/parcels/{id}/pickup", first)
                .contentType(MediaType.APPLICATION_JSON).content(OP)).andExpect(status().isOk());

        // 运单号是\"未完结唯一\"而非全局唯一：拒收重投、取错退回都会二次入库，
        // 全局唯一会把这些合法场景直接拒死
        long second = inbound("SF-REDELIVER-001", "15-1");

        assertThat(parcelRepo.findAll()).hasSize(2);
        assertThat(reload(first).getStatus()).isEqualTo(ParcelStatus.PICKED_UP);
        assertThat(reload(first).getActiveFlag()).isNull();
        assertThat(reload(second).getStatus()).isEqualTo(ParcelStatus.PENDING);
        assertThat(reload(second).getActiveFlag()).isEqualTo(1);
        // 两条记录取件码不同：第一条的码仍在冷却期，不会被复用
        assertThat(reload(second).getPickupCode()).isNotEqualTo(reload(first).getPickupCode());
    }

    // =========================================================================
    // 拒收退回 / 催取 / 撤销入库
    // =========================================================================

    @Test
    @DisplayName("P2 拒收退回：转 RETURNED，码同样进入冷却（activeFlag 空、codeSlotFlag 为 1）")
    void returnParcel() throws Exception {
        long id = inbound("SF-RETURN-001", "15-1");

        mockMvc.perform(post("/api/v1/parcels/{id}/return", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"站员B\",\"remark\":\"客户拒收，商品破损\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parcel.status").value("RETURNED"))
                .andExpect(jsonPath("$.data.parcel.remark").value("客户拒收，商品破损"))
                .andExpect(jsonPath("$.data.codeReusableAt").exists());

        Parcel p = reload(id);
        // 客户手里的旧通知同样存在，故退回与取件在\"码\"的视角下没有区别
        assertThat(p.getActiveFlag()).isNull();
        assertThat(p.getCodeSlotFlag()).isEqualTo(1);
        assertThat(p.getOutboundAt()).isNotNull();
        assertThat(eventTypes(id)).containsExactly(EventType.INBOUND, EventType.RETURN);
    }

    @Test
    @DisplayName("TC-11b 催取：次数累加、记录最后催取时间、写流水")
    void urge() throws Exception {
        long id = inbound("SF-URGE-001", "15-1");
        clock.advanceDays(3);

        mockMvc.perform(post("/api/v1/parcels/{id}/urge", id)
                        .contentType(MediaType.APPLICATION_JSON).content(OP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.urgeCount").value(1))
                .andExpect(jsonPath("$.data.lastUrgedAt").value("2026-03-05T09:00:00"))
                // 滞留 72h 以上，档位为 ALERT
                .andExpect(jsonPath("$.data.overdueLevel").value("ALERT"));

        clock.advanceDays(1);
        mockMvc.perform(post("/api/v1/parcels/{id}/urge", id)
                        .contentType(MediaType.APPLICATION_JSON).content(OP))
                .andExpect(jsonPath("$.data.urgeCount").value(2))
                .andExpect(jsonPath("$.data.lastUrgedAt").value("2026-03-06T09:00:00"));

        assertThat(eventTypes(id)).containsExactly(
                EventType.INBOUND, EventType.URGE, EventType.URGE);
    }

    @Test
    @DisplayName("F8 撤销上一件入库：以拒收退回表达，不物理删除，流水完整")
    void undoInbound() throws Exception {
        long id = inbound("SF-UNDO-001", "15-1");

        mockMvc.perform(post("/api/v1/parcels/{id}/undo-inbound", id)
                        .contentType(MediaType.APPLICATION_JSON).content(OP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RETURNED"))
                .andExpect(jsonPath("$.data.remark").value("撤销入库（扫错件）"));

        // 物理删除会破坏 INV-6，故以反向状态流转表达
        assertThat(parcelRepo.findAll()).hasSize(1);
        assertThat(eventTypes(id)).containsExactly(EventType.INBOUND, EventType.RETURN);
    }

    @Test
    @DisplayName("P2 异常件备注：不改变状态但写流水")
    void remark() throws Exception {
        long id = inbound("SF-REMARK-001", "15-1");

        mockMvc.perform(post("/api/v1/parcels/{id}/remark", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"站员B\",\"remark\":\"外包装破损，已拍照\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.remark").value("外包装破损，已拍照"));

        assertThat(eventTypes(id)).containsExactly(EventType.INBOUND, EventType.REMARK);
    }

    @Test
    @DisplayName("F10 状态流水接口：按时间正序完整还原处置过程")
    void eventsApi() throws Exception {
        long id = inbound("SF-EVENTS-001", "15-1");
        mockMvc.perform(post("/api/v1/parcels/{id}/pickup", id)
                .contentType(MediaType.APPLICATION_JSON).content(OP));
        clock.advanceMinutes(5);
        mockMvc.perform(post("/api/v1/parcels/{id}/cancel-pickup", id)
                .contentType(MediaType.APPLICATION_JSON).content(OP));

        mockMvc.perform(get("/api/v1/parcels/{id}/events", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].eventType").value("INBOUND"))
                .andExpect(jsonPath("$.data[1].eventType").value("PICKUP"))
                .andExpect(jsonPath("$.data[1].eventName").value("确认取件"))
                .andExpect(jsonPath("$.data[2].eventType").value("CANCEL_PICKUP"))
                .andExpect(jsonPath("$.data[2].eventName").value("撤销取件"));
    }

    @Test
    @DisplayName("P4004 操作不存在的包裹：404")
    void pickupNotFound() throws Exception {
        mockMvc.perform(post("/api/v1/parcels/{id}/pickup", 999999L)
                        .contentType(MediaType.APPLICATION_JSON).content(OP))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("P4004"));
    }
}
