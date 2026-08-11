package com.sf.station.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.sf.station.code.domain.PickupCodeNormalizer;
import com.sf.station.code.domain.PickupCodeVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PickupCodeNormalizerTest {

    @Test
    @DisplayName("TC-17 全角字符与前导零归一化后可识别为同一取件码")
    void tc17_normalizesFullWidthCodeAndExposesConflictIdentity() {
        PickupCodeVO scanned = PickupCodeNormalizer.normalize("１５－１－0731");
        PickupCodeVO stored = PickupCodeNormalizer.normalize("15-1-731");

        assertThat(scanned.fullCode()).isEqualTo("15-1-731");
        assertThat(scanned).isEqualTo(stored);
    }
}
