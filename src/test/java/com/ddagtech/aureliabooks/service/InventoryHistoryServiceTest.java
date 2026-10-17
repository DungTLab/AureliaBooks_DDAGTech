package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.response.InventoryMovementSummary;
import com.ddagtech.aureliabooks.dto.response.InventoryMovementSummary.MovementType;
import com.ddagtech.aureliabooks.repository.InventoryMovementRepository;
import com.ddagtech.aureliabooks.service.impl.InventoryHistoryServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryHistoryServiceTest {

    @Mock
    private InventoryMovementRepository inventoryMovementRepository;

    @InjectMocks
    private InventoryHistoryServiceImpl inventoryHistoryService;

    @Test
    void history_delegatesQueryToReadOnlyViewRepository() {
        Pageable pageable = PageRequest.of(0, 20);
        LocalDateTime from = LocalDateTime.of(2026, 10, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 10, 18, 0, 0);
        InventoryMovementSummary movement = new InventoryMovementSummary(
                10L,
                MovementType.IMPORT,
                LocalDateTime.of(2026, 10, 15, 9, 30),
                50,
                "goods_receipts",
                1L,
                100L,
                "GR-20261015-001",
                2L
        );
        Page<InventoryMovementSummary> expected = new PageImpl<>(List.of(movement), pageable, 1);

        when(inventoryMovementRepository.findMovements(10L, 2L, from, to, pageable)).thenReturn(expected);

        Page<InventoryMovementSummary> result = inventoryHistoryService.history(10L, 2L, from, to, pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().getFirst().referenceCode()).isEqualTo("GR-20261015-001");
        verify(inventoryMovementRepository).findMovements(10L, 2L, from, to, pageable);
    }
}
