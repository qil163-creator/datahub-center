package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.module.governance.dal.dataobject.quality.*;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityAuditLogMapper;
import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QualityTaskExecutorConnectionTest {
    @Test void objectOwnsAndClosesOnlyItsResolvedPoolAndConnection() throws Exception {
        var resolver = mock(MasterDatabaseResolver.class); var pool = mock(HikariDataSource.class);
        var connection = mock(Connection.class); var info = new MasterDatabaseResolver.ConnectionInfo();
        when(resolver.createDataSource(info)).thenReturn(pool); when(pool.getConnection()).thenReturn(connection);
        var executor = QualityRuntimeFixture.executor(); ReflectionTestUtils.setField(executor, "databaseResolver", resolver);
        var results = new ArrayList<QualityAuditLogDO>();
        QualityRuntimeFixture.executeObject(executor, 1L, QualityTaskDO.builder().id(2L).build(), info,
                QualityCheckObjectDO.builder().id(3L).build(), List.of(), results);
        assertTrue(results.isEmpty()); verify(resolver).createDataSource(info);
        verify(connection).close(); verify(pool).close(); verify(connection, never()).commit(); verify(connection, never()).rollback();
    }

    @Test void failedAcquisitionClosesPoolAndMarksEveryRuleWithoutRetryingAnotherDriver() throws Exception {
        var resolver = mock(MasterDatabaseResolver.class); var pool = mock(HikariDataSource.class);
        var info = new MasterDatabaseResolver.ConnectionInfo(); var mapper = mock(QualityAuditLogMapper.class);
        when(resolver.createDataSource(info)).thenReturn(pool); when(pool.getConnection()).thenThrow(new SQLException("synthetic unavailable"));
        var executor = QualityRuntimeFixture.executor(); ReflectionTestUtils.setField(executor, "databaseResolver", resolver);
        ReflectionTestUtils.setField(executor, "auditLogMapper", mapper);
        var results = new ArrayList<QualityAuditLogDO>();
        QualityRuntimeFixture.executeObject(executor, 1L, QualityTaskDO.builder().id(2L).build(), info,
                QualityCheckObjectDO.builder().id(3L).build(), List.of(QualityRuleDO.builder().id(4L).build(),
                        QualityRuleDO.builder().id(5L).build()), results);
        assertEquals(2, results.size()); assertTrue(results.stream().allMatch(r -> "ERROR".equals(r.getResultType())));
        verify(resolver, times(1)).createDataSource(info); verify(pool, times(1)).getConnection(); verify(pool).close();
        for (var result : results) verify(mapper).insert(result);
    }
}
