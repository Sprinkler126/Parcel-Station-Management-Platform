package com.sf.station.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.sf.station.support.BaseIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 关键查询的数据库索引契约，防止迁移或重构破坏覆盖索引。 */
class DatabaseIndexTest extends BaseIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("占用位图查询使用包含冷却边界与返回列的覆盖索引")
    void occupiedSequenceQueryUsesCoveringIndex() {
        List<String> columns = jdbc.queryForList("""
                select column_name from information_schema.index_columns
                where table_name = 'parcel' and index_name = 'idx_alloc'
                order by ordinal_position
                """, String.class);

        assertThat(columns).containsExactly(
                "code_prefix", "code_slot_flag", "outbound_at", "code_seq");

        String plan = jdbc.queryForObject("""
                explain select code_seq from parcel
                where code_prefix = '15-1' and code_slot_flag = 1
                  and (outbound_at is null or outbound_at > timestamp '2026-03-01 00:00:00')
                """, String.class);
        assertThat(plan).containsIgnoringCase("idx_alloc");
    }
}
