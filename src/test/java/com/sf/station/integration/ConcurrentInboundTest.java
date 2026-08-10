package com.sf.station.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.sf.station.code.domain.AllocScope;
import com.sf.station.parcel.application.InboundAppService;
import com.sf.station.parcel.application.InboundCommand;
import com.sf.station.parcel.domain.CodeSource;
import com.sf.station.parcel.domain.Parcel;
import com.sf.station.support.BaseIntegrationTest;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * TC-07 并发入库。
 *
 * <p><b>本类刻意不加 @Transactional</b>：测试事务会把所有操作困在同一个未提交事务里，
 * 子线程看不到彼此的写入，唯一索引冲突根本不会发生，用例会"假通过"。
 *
 * <p>这条用例真正验证的是 §8.3 的三层结构是否正确：
 * 事务内分配 → 约束冲突冒泡出事务边界 → 事务外重新加载位图重试。
 * 若把重试写进事务方法内部，此处会抛 UnexpectedRollbackException。
 */
class ConcurrentInboundTest extends BaseIntegrationTest {

    @Autowired
    private InboundAppService inboundService;

    private InboundCommand cmd(String trackingNo, String prefix) {
        return new InboundCommand(trackingNo, "SF", "13812345678", "张",
                CodeSource.AUTO, AllocScope.ROW, prefix, null, null, "站员A", null);
    }

    @Test
    @Tag("showcase")
    @DisplayName("TC-07 同排双线程并发入库：两码不重复且均成功")
    void tc07_concurrentInbound_shouldNotDuplicateCode() throws Exception {
        int threads = 2;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Parcel>> futures = IntStream.range(0, threads)
                    .mapToObj(i -> pool.submit(() -> {
                        start.await();
                        return inboundService.inbound(cmd("SF-CC-" + i, "15-1"));
                    }))
                    .toList();

            start.countDown();

            Set<String> codes = futures.stream()
                    .map(this::get)
                    .map(Parcel::getPickupCode)
                    .collect(Collectors.toSet());

            // 两件都成功，且取件码互不重复
            assertThat(codes).hasSize(threads);
            assertThat(parcelRepo.findAll()).hasSize(threads);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("TC-07b 同排八线程并发入库：八个码互不重复，无一失败")
    void tc07b_higherConcurrency() throws Exception {
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Parcel>> futures = IntStream.range(0, threads)
                    .mapToObj(i -> pool.submit(() -> {
                        start.await();
                        return inboundService.inbound(cmd("SF-CC8-" + i, "15-1"));
                    }))
                    .toList();

            start.countDown();

            Set<String> codes = futures.stream()
                    .map(this::get)
                    .map(Parcel::getPickupCode)
                    .collect(Collectors.toSet());

            assertThat(codes).hasSize(threads);
            assertThat(parcelRepo.findAll()).hasSize(threads);
            // 唯一索引 uk_code_slot 保证：占用中的码全局唯一
            assertThat(parcelRepo.findAll().stream().map(Parcel::getPickupCode).distinct().count())
                    .isEqualTo(threads);
        } finally {
            pool.shutdownNow();
        }
    }

    private Parcel get(Future<Parcel> f) {
        try {
            return f.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("并发入库失败：" + e.getMessage(), e);
        }
    }
}
