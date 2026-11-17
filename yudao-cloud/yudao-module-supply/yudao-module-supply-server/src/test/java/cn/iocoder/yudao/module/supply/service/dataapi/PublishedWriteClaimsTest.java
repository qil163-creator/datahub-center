package cn.iocoder.yudao.module.supply.service.dataapi;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.supply.api.dataapi.PublishedWriteClaimApi.Contract;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiOperationDO;
import cn.iocoder.yudao.module.supply.dal.mysql.dataapi.DataApiOperationMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublishedWriteClaimsTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final DataApiOperationMapper operations = mock(DataApiOperationMapper.class);
    private final PublishedWriteClaims claims = new PublishedWriteClaims(redis, operations);
    private final DataApiDO api = new DataApiDO();

    PublishedWriteClaimsTest() {
        api.setId(2L);
        api.setActiveReleaseId(3L);
    }

    @Test void issueRequiresClaimForSameAppApiAndRelease() {
        var row = row();
        row.setReleaseId(90L);
        when(operations.find(1L, 2L, "operation-key")).thenReturn(row);
        assertThrows(ServiceException.class, () -> claims.issue(1L, api, "operation-key", this::contract));
        verifyNoInteractions(redis);
    }

    @Test @SuppressWarnings("unchecked") void redemptionConsumesOnceAndRequiresAnActiveDatabaseClaim() {
        var row = row();
        when(operations.find(1L, 2L, "operation-key")).thenReturn(row);
        when(operations.activeClaim(4L)).thenReturn(row);
        var values = (ValueOperations<String, String>) mock(ValueOperations.class);
        var store = new ConcurrentHashMap<String, String>();
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), eq(Duration.ofSeconds(30))))
                .thenAnswer(call -> store.putIfAbsent(call.getArgument(0), call.getArgument(1)) == null);
        when(values.getAndDelete(anyString())).thenAnswer(call -> store.remove((String) call.getArgument(0)));
        String ticket = claims.issue(1L, api, "operation-key", this::contract);
        assertEquals(contract(4L), claims.redeem(ticket));
        assertThrows(ServiceException.class, () -> claims.redeem(ticket));
        String expired = claims.issue(1L, api, "operation-key", this::contract);
        when(operations.activeClaim(4L)).thenReturn(null);
        assertThrows(ServiceException.class, () -> claims.redeem(expired));
        assertTrue(store.isEmpty());
    }

    private DataApiOperationDO row() {
        var row = new DataApiOperationDO();
        row.setId(4L);
        row.setState("RUNNING");
        row.setAppId(1L);
        row.setApiId(2L);
        row.setReleaseId(3L);
        return row;
    }

    private Contract contract(long operation) {
        return new Contract(1, 2, 3, operation, 5, "revision", "INSERT", List.of(), List.of(), 30);
    }
}
