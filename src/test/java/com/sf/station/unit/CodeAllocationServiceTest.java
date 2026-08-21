package com.sf.station.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sf.station.code.application.CodeAllocationService;
import com.sf.station.code.domain.AllocScope;
import com.sf.station.code.domain.CodeSpace;
import com.sf.station.code.repository.CodeSpaceRepository;
import com.sf.station.parcel.repository.ParcelRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class CodeAllocationServiceTest {

    @Test
    void fullScopeUsesOneGroupedCountQueryWithoutLoadingOccupiedSequences() {
        ParcelRepository parcelRepo = mock(ParcelRepository.class);
        CodeSpaceRepository spaceRepo = mock(CodeSpaceRepository.class);
        CodeAllocationService service = new CodeAllocationService(parcelRepo, spaceRepo, null);
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 10, 0);

        CodeSpace largeRow = CodeSpace.of("15-1", 100, 7, now);
        CodeSpace smallRow = CodeSpace.of("15-2", 10, 7, now);
        when(spaceRepo.findAllEnabled()).thenReturn(List.of(largeRow, smallRow));
        when(parcelRepo.countHeldSlotsGroupedByPrefix()).thenReturn(List.of(
                occupiedCount("15-1", 10),
                occupiedCount("15-2", 2)));

        CodeSpace selected = service.resolveSpace(AllocScope.FULL, null, now);

        assertThat(selected.getPrefix()).isEqualTo("15-1");
        verify(parcelRepo).countHeldSlotsGroupedByPrefix();
        verify(parcelRepo, never()).findOccupiedSeqs(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class));
    }

    private static ParcelRepository.PrefixOccupiedCount occupiedCount(String prefix, long count) {
        return new ParcelRepository.PrefixOccupiedCount() {
            @Override
            public String getCodePrefix() {
                return prefix;
            }

            @Override
            public long getOccupiedCount() {
                return count;
            }
        };
    }
}
