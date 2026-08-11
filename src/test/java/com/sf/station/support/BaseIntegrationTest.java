package com.sf.station.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sf.station.code.domain.CodeSpace;
import com.sf.station.code.repository.CodeSpaceRepository;
import com.sf.station.code.repository.CooldownPolicyLogRepository;
import com.sf.station.parcel.domain.EventType;
import com.sf.station.parcel.domain.Parcel;
import com.sf.station.parcel.domain.ParcelEvent;
import com.sf.station.parcel.repository.ParcelEventRepository;
import com.sf.station.parcel.repository.ParcelRepository;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 集成测试基类：MockMvc + H2 + MutableClock。
 *
 * <p>刻意<b>不加 @Transactional</b>：入库主链路依赖"约束冲突冒泡出事务边界后重试"，
 * 测试事务会改变这一语义；并发用例更是必须看到跨线程可见性。
 * 因此改为每个用例前手工清库。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestClockConfig.class)
public abstract class BaseIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    protected MutableClock clock;

    @Autowired
    protected ParcelRepository parcelRepo;
    @Autowired
    protected ParcelEventRepository eventRepo;
    @Autowired
    protected CodeSpaceRepository spaceRepo;
    @Autowired
    protected CooldownPolicyLogRepository policyLogRepo;

    @BeforeEach
    void resetDatabase() {
        clock.setInstant(TestClockConfig.START.atZone(TestClockConfig.ZONE).toInstant());
        eventRepo.deleteAllInBatch();
        parcelRepo.deleteAllInBatch();
        policyLogRepo.deleteAllInBatch();
        spaceRepo.deleteAllInBatch();
        seedSpaces();
    }

    /** 默认码空间：15-1 与 15-2 常规容量，另留小容量排给具体用例自建 */
    protected void seedSpaces() {
        LocalDateTime now = now();
        spaceRepo.save(CodeSpace.of("15-1", 9999, 7, now));
        spaceRepo.save(CodeSpace.of("15-2", 9999, 7, now));
    }

    protected CodeSpace newSpace(String prefix, int capacity, int cooldownDays) {
        return spaceRepo.save(CodeSpace.of(prefix, capacity, cooldownDays, now()));
    }

    protected LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    protected String json(Object o) throws Exception {
        return objectMapper.writeValueAsString(o);
    }

    // =========================================================================
    // 请求辅助
    // =========================================================================

    /** 标准入库请求体，用例按需覆盖单个字段 */
    protected Map<String, Object> inboundBody(String trackingNo, String prefix) {
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

    /** 走 HTTP 入库一件并返回其 id，供出库类用例铺数据 */
    protected long inbound(Map<String, Object> body) throws Exception {
        String resp = mockMvc.perform(post("/api/v1/parcels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).path("data").path("id").asLong();
    }

    protected long inbound(String trackingNo, String prefix) throws Exception {
        return inbound(inboundBody(trackingNo, prefix));
    }

    /** 读取响应体中的某个 JSON 路径节点 */
    protected JsonNode node(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    protected Parcel reload(long id) {
        return parcelRepo.findById(id).orElseThrow();
    }

    protected List<EventType> eventTypes(long id) {
        return eventRepo.findByParcelIdOrderByOccurredAtAscIdAsc(id).stream()
                .map(ParcelEvent::getEventType).toList();
    }
}
