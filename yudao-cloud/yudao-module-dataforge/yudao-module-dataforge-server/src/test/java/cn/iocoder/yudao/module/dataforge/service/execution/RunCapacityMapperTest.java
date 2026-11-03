package cn.iocoder.yudao.module.dataforge.service.execution;

import cn.iocoder.yudao.module.dataforge.dal.mysql.execution.RunCapacityMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunCapacityMapperTest {
    @AfterEach
    void clearTransaction() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void refusesToCheckCapacityOutsideClaimTransaction() {
        RunCapacityMapper mapper = mock(RunCapacityMapper.class, CALLS_REAL_METHODS);
        assertThrows(IllegalStateException.class, () -> mapper.hasCapacity( 32, 2, List.of(10L)));
    }

    @Test
    void tenantLockProtectsEveryRequestedDatabaseBudget() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        RunCapacityMapper mapper = mock(RunCapacityMapper.class, CALLS_REAL_METHODS);
        when(mapper.tryLockPlatformCapacity()).thenReturn(true);
        when(mapper.countPlatformSlots()).thenReturn(1L);
        when(mapper.countDataSourceSlots( "[10]")).thenReturn(1L);
        when(mapper.countDataSourceSlots( "[20]")).thenReturn(2L);

        assertFalse(mapper.hasCapacity( 32, 2, List.of(10L, 20L)));
        verify(mapper).countDataSourceSlots( "[10]");
        verify(mapper).countDataSourceSlots( "[20]");
        assertTrue(mapper.hasCapacity( 32, 3, List.of(10L, 20L)));
    }
}
