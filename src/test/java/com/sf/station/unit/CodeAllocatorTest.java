package com.sf.station.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.sf.station.code.domain.CodeAllocator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CodeAllocatorTest {

    @Test
    @DisplayName("TC-06 游标接近上限时正确回绕至 1 并跳过已占用号")
    void tc06_wrapsAndSkipsOccupiedSlots() {
        assertThat(CodeAllocator.nextFit(5, 5, List.of(1, 2)))
                .hasValue(3);
    }
}
