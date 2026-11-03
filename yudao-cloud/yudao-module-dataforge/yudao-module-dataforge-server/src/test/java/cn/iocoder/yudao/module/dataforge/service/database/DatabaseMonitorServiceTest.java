package cn.iocoder.yudao.module.dataforge.service.database;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.IMetaDataByJdbcService;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.DatabaseMonitorVO.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.*;
import cn.iocoder.yudao.module.dataforge.service.access.AccessDatabaseResolver;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;

import java.time.LocalDateTime;
import java.util.List;

import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DatabaseMonitorServiceTest {
    private final DatabaseMapper databases = mock(DatabaseMapper.class);
    private final DatabaseMonitorMapper monitor = mock(DatabaseMonitorMapper.class);
    private final DatabaseMonitorSampleMapper samples = mock(DatabaseMonitorSampleMapper.class);
    private final DatabaseAlarmRuleMapper rules = mock(DatabaseAlarmRuleMapper.class);
    private final DatabaseAlarmRecordMapper alarms = mock(DatabaseAlarmRecordMapper.class);
    private final AccessDatabaseResolver resolver = mock(AccessDatabaseResolver.class);
    private final DbSwitchRuntime runtime = mock(DbSwitchRuntime.class);
    private final DatabaseMonitorSampleService writer = mock(DatabaseMonitorSampleService.class);
    private final DatabaseMonitorService service = new DatabaseMonitorService(databases, monitor, samples, rules,
            alarms, resolver, runtime, writer);
    private DatabaseDO database;

    @BeforeEach void setup() {
        TenantContextHolder.clear();
        for (Class<?> type : List.of(DatabaseDO.class, DatabaseMonitorSampleDO.class, DatabaseAlarmRuleDO.class, DatabaseAlarmRecordDO.class))
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), type.getSimpleName()), type);
        database = DatabaseDO.builder().id(22L).status(0).build();
        database.setUpdateTime(LocalDateTime.of(2026, 1, 1, 0, 0));
        when(databases.selectById(22L)).thenReturn(database);
    }
    @AfterEach void cleanup() { TenantContextHolder.clear(); }

    @Test void duplicateMinuteDoesNotConnectOrWriteAnotherSample() {
        when(monitor.claim( eq(22L), any(), any(), anyString())).thenReturn(null);
        service.collectSample(22L);
        verifyNoInteractions(resolver, runtime, writer);
    }

    @Test void missingOrDisabledDatabaseCannotClaimOrProbe() {
        assertEquals(DATABASE_NOT_EXISTS.getCode(),
                assertThrows(ServiceException.class, () -> service.collectSample(99L)).getCode());
        database.setStatus(1);
        assertEquals(DATABASE_UNAVAILABLE.getCode(),
                assertThrows(ServiceException.class, () -> service.collectSample(22L)).getCode());
        verifyNoInteractions(monitor, resolver, runtime, writer);
    }

    @Test void capacityFailureKeepsSuccessfulConnectionAndCommitsOnlyAfterExternalCalls() throws Exception {
        var metadata = prepareConnection();
        doThrow(new UnsupportedOperationException("not supported")).when(metadata)
                .queryDatabaseUsedBytes("jdbc:fixture:monitor", "fixture", "fixture-secret", 5_000);
        service.collectSample(22L);
        var order = inOrder(metadata, writer);
        order.verify(metadata).probeConnection(anyString(), anyString(), anyString(), anyInt());
        order.verify(metadata).queryDatabaseUsedBytes(anyString(), anyString(), anyString(), eq(5_000));
        order.verify(writer).complete(eq(22L), eq(33L), anyString(), eq(database.getUpdateTime()), any(), eq(1), anyLong(), isNull(), eq("SUCCESS"), anyString());
        verify(monitor, never()).lockDatabase( anyLong());
    }

    @Test void probeFailureWithoutNetworkEvidencePersistsUnknownNeverDriverMessage() throws Exception {
        var metadata = prepareConnection();
        doThrow(new IllegalStateException("jdbc secret fixture-secret")).when(metadata)
                .probeConnection(anyString(), anyString(), anyString(), anyInt());
        service.collectSample(22L);
        verify(writer).complete(eq(22L), eq(33L), anyString(), eq(database.getUpdateTime()), any(), eq(0), isNull(), isNull(), eq("FAILED"), anyString());
        verify(metadata, never()).queryDatabaseUsedBytes(anyString(), anyString(), anyString(), anyInt());
    }

    @Test void missingDatabaseCannotReadAnotherDatabaseRules() {
        assertEquals(DATABASE_NOT_EXISTS.getCode(), assertThrows(ServiceException.class, () -> service.rule(99L, 4L)).getCode());
        verifyNoInteractions(rules);
        var otherRule = new DatabaseAlarmRuleDO(); otherRule.setId(4L); otherRule.setDatabaseId(99L);
        when(rules.selectById(4L)).thenReturn(otherRule);
        assertEquals(DATABASE_MONITOR_RULE_NOT_EXISTS.getCode(), assertThrows(ServiceException.class, () -> service.rule(22L, 4L)).getCode());
    }

    @Test void trendKeepsMissingSamplesNullAndRejectsUnboundedHistory() {
        when(monitor.logTrend( eq(22L), any())).thenReturn(List.of());
        when(monitor.sampleTrend( eq(22L), any())).thenReturn(List.of());
        Trend result = service.trend(22L, 7);
        assertEquals(7, result.getPoints().size());
        assertTrue(result.getPoints().stream().allMatch(point -> point.getResponseTimeMs() == null && point.getReadSpeed() == null));
        assertTrue(result.getPoints().stream().allMatch(point -> "0".equals(point.getInputRows())));
        assertEquals(DATABASE_MONITOR_RANGE_INVALID.getCode(), assertThrows(ServiceException.class, () -> service.trend(22L, 365)).getCode());
    }

    @Test void exactThresholdComparisonAndConnectionRulesAreValidated() {
        var req = new RuleSave(); req.setMetric("RESPONSE_TIME"); req.setOperator("GTE"); req.setThreshold("900719925474.123456");
        assertDoesNotThrow(() -> DatabaseMonitorService.validateRule(req));
        assertTrue(DatabaseMonitorSampleService.matches("GTE", req.getThreshold(), "900719925474.123456"));
        assertFalse(DatabaseMonitorSampleService.matches("GT", req.getThreshold(), "900719925474.123456"));
        assertTrue(DatabaseMonitorSampleService.matches("LT", req.getThreshold(), "900719925474.123455"));
        req.setMetric("CONNECTION_STATUS"); req.setOperator("EQ"); req.setThreshold("0");
        assertDoesNotThrow(() -> DatabaseMonitorService.validateRule(req));
        req.setThreshold("1");
        assertThrows(ServiceException.class, () -> DatabaseMonitorService.validateRule(req));
        req.setMetric("CPU");
        assertThrows(ServiceException.class, () -> DatabaseMonitorService.validateRule(req));
    }

    @Test void changedConnectionRevisionDiscardsLateResultBeforePublishing() {
        when(monitor.lockDatabase( 22L)).thenReturn(22L);
        var actualWriter = new DatabaseMonitorSampleService(monitor, databases, samples, rules, alarms);
        actualWriter.complete(22L, 33L, "token", database.getUpdateTime().minusSeconds(1), LocalDateTime.now(), 1, 5L, null, "SUCCESS", "采集成功");
        verify(samples).update(isNull(), any());
        verify(monitor, never()).complete(anyLong(), anyLong(), anyString(), any(), anyInt(), any(), any(), anyString(), anyString());
        verifyNoInteractions(rules, alarms);
    }

    @Test void expiredLeaseTokenCannotCreateAlarmEvenWhenConditionMatches() {
        when(monitor.lockDatabase( 22L)).thenReturn(22L);
        when(monitor.complete(anyLong(), anyLong(), anyString(), any(), anyInt(), any(), any(), anyString(), anyString())).thenReturn(0);
        var actualWriter = new DatabaseMonitorSampleService(monitor, databases, samples, rules, alarms);
        actualWriter.complete(22L, 33L, "old-token", database.getUpdateTime(), LocalDateTime.now(), 2, null, null, "FAILED", "连接失败");
        verifyNoInteractions(rules, alarms);
    }

    @Test void collectionUsesPlatformScopeWithoutTenantContext() {
        TenantContextHolder.clear();
        when(monitor.claim(eq(22L), any(), any(), anyString())).thenReturn(null);
        assertDoesNotThrow(() -> service.collectSample(22L));
        verify(monitor).claim(eq(22L), any(), any(), anyString());
        verifyNoInteractions(resolver, runtime, writer);
    }

    @Test void failureClassificationDistinguishesUnsupportedAuthenticationAndConnectivity() {
        assertEquals("UNSUPPORTED", DatabaseMonitorService.classifyFailure(new RuntimeException(new java.sql.SQLFeatureNotSupportedException())));
        assertEquals("OFFLINE", DatabaseMonitorService.classifyFailure(new RuntimeException(new java.sql.SQLException("fixture", "08001"))));
        assertEquals("OFFLINE", DatabaseMonitorService.classifyFailure(new java.net.ConnectException("fixture")));
        assertEquals("FAILED", DatabaseMonitorService.classifyFailure(new java.sql.SQLException("fixture", "28000")));
        assertEquals("FAILED", DatabaseMonitorService.classifyFailure(new java.sql.SQLException("fixture", "42501")));
    }

    private IMetaDataByJdbcService prepareConnection() {
        when(monitor.claim( eq(22L), any(), any(), anyString())).thenReturn(33L);
        var connection = new AccessDatabaseResolver.ConnectionInfo();
        connection.setProductType(ProductTypeEnum.POSTGRESQL); connection.setJdbcUrl("jdbc:fixture:monitor");
        connection.setUsername("fixture"); connection.setPassword("fixture-secret");
        when(resolver.resolve(database)).thenReturn(connection);
        var metadata = mock(MetaDataByJdbcServiceImpl.class);
        when(runtime.jdbcMetadata(ProductTypeEnum.POSTGRESQL)).thenReturn(metadata);
        return metadata;
    }
}
