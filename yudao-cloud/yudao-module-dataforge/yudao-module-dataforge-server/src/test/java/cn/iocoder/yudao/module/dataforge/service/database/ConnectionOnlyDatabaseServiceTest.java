package cn.iocoder.yudao.module.dataforge.service.database;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.databasetype.DatabaseTypeDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.databasetype.DatabaseTypeMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConnectionOnlyDatabaseServiceTest {
    private DatabaseServiceImpl service;
    private DatabaseMapper mapper;
    private ConnectionOnlyDiagnosis diagnosis;
    private DbSwitchRuntime runtime;
    private DatabaseDO stored;
    private static final String CONFIG = "{\"schemaVersion\":1,\"endpoint\":\"https://storage.example.invalid\",\"pathStyle\":true}";

    @BeforeEach void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "connection-only-test"), DatabaseDO.class);
        service = new DatabaseServiceImpl(); mapper = mock(DatabaseMapper.class);
        var types = mock(DatabaseTypeMapper.class); diagnosis = mock(ConnectionOnlyDiagnosis.class);
        runtime = mock(DbSwitchRuntime.class); var permissions = mock(SecurityFrameworkService.class);
        ReflectionTestUtils.setField(service, "databaseMapper", mapper);
        ReflectionTestUtils.setField(service, "databaseTypeMapper", types);
        ReflectionTestUtils.setField(service, "connectionOnlyDiagnosis", diagnosis);
        ReflectionTestUtils.setField(service, "dbSwitchRuntime", runtime);
        ReflectionTestUtils.setField(service, "securityFrameworkService", permissions);
        when(permissions.hasPermission(anyString())).thenReturn(true);
        when(types.selectById(41L)).thenReturn(DatabaseTypeDO.builder().id(41L).typeCode("MINIO").typeName("MinIO").build());
        stored = DatabaseDO.builder().id(101L).name("测试存储").databaseType(41).databaseIp("").databasePort("")
                .databaseName("fixture-bucket").databaseSchema("").userName("fixture-user").jdbcUrl("")
                .extendConfig(CONFIG).secretKey("fixture-record-key").build();
        stored.setPassword(DatabasePasswordEncryptUtils.encrypt("fixture-password", stored.getSecretKey()));
        when(mapper.selectById(101L)).thenReturn(stored);
        when(mapper.selectDiagnosticStartTime()).thenReturn(LocalDateTime.now());
    }

    private DatabaseSaveReqVO request() {
        var req = BeanUtils.toBean(stored, DatabaseSaveReqVO.class); req.setPassword(null); return req;
    }

    @Test void creationStoresEncryptedSecretsAndNoInventedJdbcAddress() {
        var req = request(); req.setId(null); req.setDatabaseIp(null); req.setDatabasePort(null);
        req.setSecretKey("client-must-not-own-key"); req.setPassword("fixture-password");
        service.createDatabase(req);
        var saved = ArgumentCaptor.forClass(DatabaseDO.class); verify(mapper).insert(saved.capture());
        assertEquals("", saved.getValue().getDatabaseIp()); assertEquals("", saved.getValue().getJdbcUrl());
        assertNotEquals(req.getSecretKey(), saved.getValue().getSecretKey());
        assertNotEquals(req.getPassword(), saved.getValue().getPassword());
        assertEquals(req.getPassword(), DatabasePasswordEncryptUtils.decrypt(saved.getValue().getPassword(), saved.getValue().getSecretKey()));
    }

    @Test void editingTargetCannotBorrowTheStoredSecretThroughSaveThenTest() {
        var req = request(); req.setExtendConfig(CONFIG.replace("storage.example.invalid", "other.example.invalid"));
        assertThrows(ServiceException.class, () -> service.updateDatabase(req));
        verify(mapper, never()).updateById(any(DatabaseDO.class));
        verifyNoInteractions(runtime, diagnosis);
    }

    @Test void editingAnUnchangedConnectionKeepsTheServerKeyAndCiphertext() {
        var req = request(); req.setSecretKey("untrusted-request-key"); req.setRemark("新备注");
        service.updateDatabase(req);
        var saved = ArgumentCaptor.forClass(DatabaseDO.class); verify(mapper).updateById(saved.capture());
        assertEquals(stored.getSecretKey(), saved.getValue().getSecretKey());
        assertEquals(stored.getPassword(), saved.getValue().getPassword());
    }

    @Test void savedAndUnsavedTestsUseTheSameProtocolAdapterWithoutMetadata() {
        var success = new DatabaseDiagnosisRespVO(); success.setConnectionSuccessful(true); success.setOverallStatus("PASS");
        when(diagnosis.diagnose(any(), any(), anyString(), any())).thenReturn(success);
        service.testDatabase(request());
        service.testDatabase(101L);
        var req = new DatabaseDiagnosisReqVO(); req.setDatabaseId(101L);
        assertTrue(service.diagnoseDatabase(req).isConnectionSuccessful());
        verify(diagnosis, times(3)).diagnose(any(), any(), eq("fixture-password"), any());
        verifyNoInteractions(runtime);
    }

    @Test void configurationSecretsNeverEnterThePublicJsonColumn() {
        var req = request(); req.setId(null); req.setExtendConfig("{\"schemaVersion\":1,\"secretKey\":\"fixture\"}");
        assertThrows(ServiceException.class, () -> service.createDatabase(req));
        verify(mapper, never()).insert(any(DatabaseDO.class));
    }

    @Test void switchingToExplicitAnonymousModeClearsStoredCredentials() {
        var types = (DatabaseTypeMapper) ReflectionTestUtils.getField(service, "databaseTypeMapper");
        when(types.selectById(41L)).thenReturn(DatabaseTypeDO.builder().id(41L).typeCode("ROCKETMQ").build());
        stored.setExtendConfig("{\"schemaVersion\":1,\"servers\":[\"broker.example.invalid:9876\"],\"authenticationMode\":\"ACL\"}");
        var req = request();
        req.setExtendConfig("{\"schemaVersion\":1,\"servers\":[\"broker.example.invalid:9876\"],\"authenticationMode\":\"NONE\"}");
        service.updateDatabase(req);
        var saved = ArgumentCaptor.forClass(DatabaseDO.class); verify(mapper).updateById(saved.capture());
        assertEquals("", saved.getValue().getPassword());
    }

    @Test void changingOnlyTimeoutKeepsCredentialForEditingAndUnsavedTest() {
        var req = request();
        req.setExtendConfig(CONFIG.replace("\"pathStyle\":true", "\"pathStyle\":true,\"timeoutSeconds\":25"));
        service.updateDatabase(req);
        var saved = ArgumentCaptor.forClass(DatabaseDO.class); verify(mapper).updateById(saved.capture());
        assertEquals(stored.getPassword(), saved.getValue().getPassword());
        var success = new DatabaseDiagnosisRespVO(); success.setConnectionSuccessful(true); success.setOverallStatus("PASS");
        when(diagnosis.diagnose(any(), any(), anyString(), any())).thenReturn(success);
        service.testDatabase(req);
        verify(diagnosis).diagnose(any(), any(), eq("fixture-password"), any());
    }

    @Test void rewritingEquivalentDefaultConfigurationDoesNotRequireReenteringSecret() {
        stored.setExtendConfig("{\"schemaVersion\":1,\"endpoint\":\"https://storage.example.invalid\"}");
        var req = request(); req.setExtendConfig(CONFIG);
        service.updateDatabase(req);
        var saved = ArgumentCaptor.forClass(DatabaseDO.class); verify(mapper).updateById(saved.capture());
        assertEquals(stored.getPassword(), saved.getValue().getPassword());
    }
}
