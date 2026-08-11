package com.sf.station.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sf.station.parcel.domain.EventType;
import com.sf.station.support.BaseIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** 分层检索、滞留分级、批量取件、尾号补录的 MockMvc 用例（D3）。 */
class QueryApiTest extends BaseIntegrationTest {

    private static final String OP = "{\"operator\":\"站员B\"}";

    // =========================================================================
    // TC-10 分层检索
    // =========================================================================

    @Test
    @DisplayName("TC-10 按真实后四位查询：返回该尾号全部 PENDING，含脱敏号")
    void tc10_searchBySuffix() throws Exception {
        inbound("SF-Q-001", "15-1");                       // 13812345678 → 5678
        inbound("SF-Q-002", "15-1");
        Map<String, Object> other = inboundBody("SF-Q-003", "15-1");
        other.put("contactNo", "13900001111");             // → 1111
        inbound(other);

        mockMvc.perform(get("/api/v1/parcels").param("keyword", "5678"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.content[0].contactMasked").value("138****5678"))
                .andExpect(jsonPath("$.data.content[0].realSuffix").value("5678"))
                // 列表须提供足够的二次核对信息：尾号撞号在一个站点内很常见
                .andExpect(jsonPath("$.data.content[0].trackingTail").exists())
                .andExpect(jsonPath("$.data.content[0].pickupCode").exists())
                .andExpect(jsonPath("$.data.content[0].courier").value("SF"))
                .andExpect(jsonPath("$.data.content[0].receiverName").value("张"));
    }

    @Test
    @DisplayName("F2 检索通道自动判断：取件码 / 尾号 / 联系号 / 运单号四路各走等值匹配")
    void searchChannelAutoDetect() throws Exception {
        long id = inbound("SF9988776655", "15-1");

        // 形如 n-n-n → 取件码通道，且比对前会归一化（15-1-01 == 15-1-1）
        mockMvc.perform(get("/api/v1/parcels").param("keyword", "15-1-01"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(id));
        // 4 位数字 → 真实尾号
        mockMvc.perform(get("/api/v1/parcels").param("keyword", "5678"))
                .andExpect(jsonPath("$.data.total").value(1));
        // 11 位手机号 → 联系号精确匹配
        mockMvc.perform(get("/api/v1/parcels").param("keyword", "13812345678"))
                .andExpect(jsonPath("$.data.total").value(1));
        // 其余 → 运单号
        mockMvc.perform(get("/api/v1/parcels").param("keyword", "SF9988776655"))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    @DisplayName("F2 姓名与尾号等条件可组合查询，所有已填条件按 AND 生效")
    void searchByNameAndMultipleConditions() throws Exception {
        Map<String, Object> target = inboundBody("SF-MULTI-001", "15-1");
        target.put("receiverName", "张小明");
        target.put("contactNo", "13812345678");
        long targetId = inbound(target);

        Map<String, Object> sameName = inboundBody("SF-MULTI-002", "15-1");
        sameName.put("receiverName", "张小明");
        sameName.put("contactNo", "13900001111");
        inbound(sameName);

        Map<String, Object> sameSuffix = inboundBody("SF-MULTI-003", "15-1");
        sameSuffix.put("receiverName", "李小明");
        sameSuffix.put("contactNo", "18800005678");
        inbound(sameSuffix);

        mockMvc.perform(get("/api/v1/parcels")
                        .param("receiverName", "小明")
                        .param("realSuffix", "5678")
                        .param("trackingNo", "SF-MULTI-001")
                        .param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(targetId));
    }

    @Test
    @DisplayName("F3 取件码命中后只追加同姓名且同真实尾号的其他在库包裹")
    void pickupCompanionsRequireSameNameAndSuffix() throws Exception {
        Map<String, Object> source = inboundBody("SF-COMPANION-001", "15-1");
        source.put("receiverName", "王芳");
        source.put("contactNo", "13812345678");
        long sourceId = inbound(source);

        Map<String, Object> companion = inboundBody("SF-COMPANION-002", "15-1");
        companion.put("receiverName", "王芳");
        companion.put("contactNo", "18800005678");
        long companionId = inbound(companion);

        Map<String, Object> differentName = inboundBody("SF-COMPANION-003", "15-1");
        differentName.put("receiverName", "王强");
        differentName.put("contactNo", "18800005678");
        inbound(differentName);

        Map<String, Object> differentSuffix = inboundBody("SF-COMPANION-004", "15-1");
        differentSuffix.put("receiverName", "王芳");
        differentSuffix.put("contactNo", "18800001111");
        inbound(differentSuffix);

        mockMvc.perform(get("/api/v1/parcels/{id}/pickup-companions", sourceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(companionId));

        mockMvc.perform(post("/api/v1/parcels/{id}/pickup", companionId)
                        .contentType(MediaType.APPLICATION_JSON).content(OP))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/parcels/{id}/pickup-companions", sourceId))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @DisplayName("F2 查无结果返回空列表（200）而非报错")
    void searchNoResultReturnsEmptyList() throws Exception {
        inbound("SF-Q-100", "15-1");
        mockMvc.perform(get("/api/v1/parcels").param("keyword", "0000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.content").isEmpty());
    }

    @Test
    @DisplayName("F2 默认排序：在库优先，滞留最久者前置")
    void searchOrdersMostOverdueFirst() throws Exception {
        long old = inbound("SF-Q-OLD", "15-1");
        clock.advanceDays(3);
        long fresh = inbound("SF-Q-FRESH", "15-1");
        // 再取走一件作为终态记录，验证在库优先
        long done = inbound("SF-Q-DONE", "15-1");
        mockMvc.perform(post("/api/v1/parcels/{id}/pickup", done)
                .contentType(MediaType.APPLICATION_JSON).content(OP));

        mockMvc.perform(get("/api/v1/parcels").param("codePrefix", "15-1"))
                .andExpect(jsonPath("$.data.content[0].id").value(old))
                .andExpect(jsonPath("$.data.content[1].id").value(fresh))
                .andExpect(jsonPath("$.data.content[2].id").value(done));
    }

    // =========================================================================
    // TC-13 滞留分级
    // =========================================================================

    @Test
    @Tag("showcase")
    @DisplayName("TC-13 47h59m 与 48h 整：前者不标滞留，后者标滞留")
    void tc13_overdueBoundary() throws Exception {
        long id = inbound("SF-OVERDUE-001", "15-1");

        clock.advance(java.time.Duration.ofMinutes(47 * 60 + 59));
        mockMvc.perform(get("/api/v1/parcels/{id}", id))
                .andExpect(jsonPath("$.data.overdueLevel").value("NORMAL"))
                .andExpect(jsonPath("$.data.overdueMinutes").value(47 * 60 + 59))
                .andExpect(jsonPath("$.data.overdueText").value("已滞留 1 天 23 小时"));

        clock.advanceMinutes(1);   // 恰好 48h 整
        mockMvc.perform(get("/api/v1/parcels/{id}", id))
                .andExpect(jsonPath("$.data.overdueLevel").value("WARN"))
                .andExpect(jsonPath("$.data.overdueMinutes").value(48 * 60));

        clock.advanceHours(24);    // 72h 整
        mockMvc.perform(get("/api/v1/parcels/{id}", id))
                .andExpect(jsonPath("$.data.overdueLevel").value("ALERT"));
    }

    @Test
    @DisplayName("F4 滞留档位过滤：SQL 侧翻译为 inbound_at 区间，与展示口径严格一致")
    void filterByOverdueLevel() throws Exception {
        long alert = inbound("SF-OD-ALERT", "15-1");
        clock.advanceHours(25);
        long warn = inbound("SF-OD-WARN", "15-1");
        clock.advanceHours(49);          // alert 已 74h，warn 已 49h
        long normal = inbound("SF-OD-NORMAL", "15-1");

        mockMvc.perform(get("/api/v1/parcels").param("overdue", "ALERT"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(alert));
        mockMvc.perform(get("/api/v1/parcels").param("overdue", "WARN"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(warn));
        mockMvc.perform(get("/api/v1/parcels").param("overdue", "NORMAL"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(normal));
    }

    @Test
    @DisplayName("F4 已取件的包裹不参与滞留统计")
    void pickedUpParcelIsNotOverdue() throws Exception {
        long id = inbound("SF-OD-PICKED", "15-1");
        clock.advanceDays(5);
        mockMvc.perform(post("/api/v1/parcels/{id}/pickup", id)
                .contentType(MediaType.APPLICATION_JSON).content(OP));

        mockMvc.perform(get("/api/v1/parcels/{id}", id))
                .andExpect(jsonPath("$.data.overdueLevel").value("NORMAL"))
                .andExpect(jsonPath("$.data.overdueMinutes").value(0));
        mockMvc.perform(get("/api/v1/parcels").param("overdue", "ALERT"))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    // =========================================================================
    // TC-14 批量取件
    // =========================================================================

    @Test
    @Tag("showcase")
    @DisplayName("TC-14 批量取件其中一件已取走：部分成功，返回成功与失败明细")
    void tc14_batchPickupPartialFailure() throws Exception {
        long a = inbound("SF-BATCH-A", "15-1");
        long b = inbound("SF-BATCH-B", "15-1");
        long c = inbound("SF-BATCH-C", "15-1");

        // 其中一件已被家人先行取走
        mockMvc.perform(post("/api/v1/parcels/{id}/pickup", b)
                .contentType(MediaType.APPLICATION_JSON).content(OP)).andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/parcels/pickup-batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + a + "," + b + "," + c + "],\"operator\":\"站员B\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.succeeded").value(2))
                .andExpect(jsonPath("$.data.failed").value(1))
                .andExpect(jsonPath("$.data.failures[0].key").value(b))
                .andExpect(jsonPath("$.data.failures[0].code").value("P2005"));

        // 部分失败不整体回滚：成功的两件确实已出库
        assertThat(reload(a).getOutboundAt()).isNotNull();
        assertThat(reload(c).getOutboundAt()).isNotNull();
    }

    @Test
    @DisplayName("F9 按真实后四位聚合批量取件：聚合键是 realSuffix 而非 contactNo")
    void batchPickupAggregatesByRealSuffix() throws Exception {
        // 同一客户的三件包裹，其中两件走 AXB 虚拟号——每单一个不同的中间号。
        // 若按 contactNo 聚合，这三件会被拆成三组，批量取件直接失效。
        inbound("SF-AGG-001", "15-1");                                   // 真实号 → 5678
        Map<String, Object> axb1 = inboundBody("SF-AGG-002", "15-1");
        axb1.put("contactNo", "17011112222,1001");
        axb1.put("manualSuffix", "5678");
        inbound(axb1);
        Map<String, Object> axb2 = inboundBody("SF-AGG-003", "15-1");
        axb2.put("contactNo", "17033334444,2002");                       // 不同的虚拟号
        axb2.put("manualSuffix", "5678");
        inbound(axb2);

        mockMvc.perform(post("/api/v1/parcels/pickup-batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"realSuffix\":\"5678\",\"operator\":\"站员B\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.succeeded").value(3))
                .andExpect(jsonPath("$.data.failed").value(0));
    }

    @Test
    @DisplayName("F9 尾号下无待取件：404，提示可能是隐私单")
    void batchPickupNoPending() throws Exception {
        mockMvc.perform(post("/api/v1/parcels/pickup-batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"realSuffix\":\"9999\",\"operator\":\"站员B\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("P4004"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("可能是隐私单")));
    }

    // =========================================================================
    // TC-16 虚拟号入库 + 补录尾号
    // =========================================================================

    @Test
    @DisplayName("TC-16 AXB 虚拟号入库 + 补录尾号：初始 suffix 空，补录后可检索")
    void tc16_virtualNumberSuffixPatch() throws Exception {
        Map<String, Object> body = inboundBody("SF-AXB-100", "15-1");
        body.put("contactNo", "17012345678,8462");
        long id = inbound(body);

        // 入库时尾号未知，检索通道缺失
        mockMvc.perform(get("/api/v1/parcels/{id}", id))
                .andExpect(jsonPath("$.data.contactType").value("VIRTUAL"))
                .andExpect(jsonPath("$.data.realSuffix").doesNotExist())
                .andExpect(jsonPath("$.data.needsSuffixPatch").value(true));
        mockMvc.perform(get("/api/v1/parcels").param("keyword", "4321"))
                .andExpect(jsonPath("$.data.total").value(0));

        // 补录入口嵌在\"查询无结果\"处，使数据补全发生在真实作业动线中
        mockMvc.perform(patch("/api/v1/parcels/{id}/suffix", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"realSuffix\":\"4321\",\"operator\":\"站员B\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.realSuffix").value("4321"))
                .andExpect(jsonPath("$.data.suffixSource").value("MANUAL"))
                .andExpect(jsonPath("$.data.needsSuffixPatch").value(false));

        // 补录后即可按尾号检索与批量取件
        mockMvc.perform(get("/api/v1/parcels").param("keyword", "4321"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(id));

        assertThat(eventTypes(id)).containsExactly(EventType.INBOUND, EventType.SUFFIX_PATCH);
    }

    @Test
    @DisplayName("F14 补录尾号格式非法：400 / P1001")
    void patchSuffixInvalid() throws Exception {
        long id = inbound("SF-AXB-101", "15-1");
        mockMvc.perform(patch("/api/v1/parcels/{id}/suffix", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"realSuffix\":\"12\",\"operator\":\"站员B\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("P1001"));
    }

    // =========================================================================
    // TC-03 next-fit 不复用刚释放的码
    // =========================================================================

    @Test
    @Tag("showcase")
    @DisplayName("TC-03 取走中间一件后再入库：新码继续向后，不复用刚释放的码")
    void tc03_nextFitDoesNotReuseJustFreedCode() throws Exception {
        long p1 = inbound("SF-NF-001", "15-1");    // 15-1-1
        long p2 = inbound("SF-NF-002", "15-1");    // 15-1-2
        long p3 = inbound("SF-NF-003", "15-1");    // 15-1-3

        // 取走中间一件，15-1-2 出库
        mockMvc.perform(post("/api/v1/parcels/{id}/pickup", p2)
                .contentType(MediaType.APPLICATION_JSON).content(OP)).andExpect(status().isOk());

        // next-fit 从游标（3）继续向后，而不是回头捡最小空闲号 2。
        // first-fit 会立刻复用刚释放的 15-1-2 —— 客户手里的旧通知还在，
        // 取错件风险极高；且站员要回头找位置，打断流水作业。
        mockMvc.perform(post("/api/v1/parcels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(inboundBody("SF-NF-004", "15-1"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.pickupCode").value("15-1-4"));

        assertThat(reload(p1).getPickupCode()).isEqualTo("15-1-1");
        assertThat(reload(p3).getPickupCode()).isEqualTo("15-1-3");
        // 15-1-2 仍被冷却占用，槽位未释放
        assertThat(reload(p2).getCodeSlotFlag()).isEqualTo(1);
    }
}
