package cn.iocoder.yudao.module.dataforge.service.database;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticOptions;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.DatabaseDiagnosisReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.DatabaseDiagnosisRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.DatabaseSaveReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.databasetype.DatabaseTypeDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.databasetype.DatabaseTypeMapper;
import cn.iocoder.yudao.module.dataforge.enums.database.DatabaseConnectionStatusEnum;
import cn.iocoder.yudao.module.dataforge.service.access.AccessDatabaseResolver;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATABASE_CONNECT_FAILED;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.OPERATION_PERMISSION_DENIED;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DatabaseDiagnosisServiceTest {

    private static final LocalDateTime STARTED = LocalDateTime.of(2026, 10, 15, 10, 0);
    private static final String TEST_PASSWORD = "synthetic-diagnosis-password";
    private DatabaseMapper mapper;
    private DbSwitchRuntime runtime;
    private SecurityFrameworkService security;
    private AccessDatabaseResolver resolver;
    private DatabaseServiceImpl service;
    private DatabaseDO stored;
    private DatabaseTypeMapper types;
    private final List<LambdaUpdateWrapper<DatabaseDO>> writes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "diagnosis-test"), DatabaseDO.class);
        mapper = mock(DatabaseMapper.class);
        runtime = mock(DbSwitchRuntime.class);
        security = mock(SecurityFrameworkService.class);
        resolver = mock(AccessDatabaseResolver.class);
        types = mock(DatabaseTypeMapper.class);
        service = new DatabaseServiceImpl();
        var connectionDiagnosis = new ConnectionOnlyDiagnosis();
        ReflectionTestUtils.setField(connectionDiagnosis, "runtime", runtime);
        ReflectionTestUtils.setField(service, "connectionOnlyDiagnosis", connectionDiagnosis);
        ReflectionTestUtils.setField(service, "databaseMapper", mapper);
        ReflectionTestUtils.setField(service, "dbSwitchRuntime", runtime);
        ReflectionTestUtils.setField(service, "securityFrameworkService", security);
        ReflectionTestUtils.setField(service, "accessDatabaseResolver", resolver);
        ReflectionTestUtils.setField(service, "databaseTypeMapper", types);
        when(security.hasPermission("dataforge:database:query")).thenReturn(true);
        when(resolver.resolveProductType(any())).thenReturn(ProductTypeEnum.POSTGRESQL);
        when(types.selectById(1L)).thenReturn(DatabaseTypeDO.builder().id(1L).typeCode("POSTGRESQL").build());
        stored = DatabaseDO.builder().id(19L).databaseType(1).databaseIp("localhost").databasePort("5432")
                .databaseName("example").databaseSchema("public").userName("synthetic_user")
                .jdbcUrl("jdbc:postgresql://localhost:5432/example").extendConfig("connectTimeout=5")
                .secretKey("synthetic-storage-key").status(0).build();
        stored.setPassword(DatabasePasswordEncryptUtils.encrypt(TEST_PASSWORD, stored.getSecretKey()));
        stored.setUpdateTime(STARTED.minusDays(1));
        when(mapper.selectById(19L)).thenReturn(stored);
        when(mapper.selectDiagnosticStartTime()).thenReturn(STARTED);
        when(runtime.diagnoseConnection(any(), any(), any(), any(), any(), any())).thenReturn(success());
        writes.clear();
        when(mapper.update(isNull(), any())).thenAnswer(invocation -> {
            LambdaUpdateWrapper<DatabaseDO> update = invocation.getArgument(1);
            update.getSqlSet();
            update.getSqlSegment();
            writes.add(update);
            return 1;
        });
    }

    @Test
    void serviceEnforcesQueryPermissionBeforeResolvingCredentials() {
        when(security.hasPermission("dataforge:database:query")).thenReturn(false);
        ServiceException error = assertThrows(ServiceException.class, () -> service.diagnoseDatabase(saved(false)));
        assertEquals(OPERATION_PERMISSION_DENIED.getCode(), error.getCode());
        verifyNoInteractions(mapper, runtime);
    }

    @Test
    void writeChecksRequireAdditionalUpdatePermissionBeforeConnection() {
        ServiceException error = assertThrows(ServiceException.class, () -> service.diagnoseDatabase(saved(true)));
        assertEquals(OPERATION_PERMISSION_DENIED.getCode(), error.getCode());
        verifyNoInteractions(mapper, runtime);
    }

    @Test
    void savedDiagnosisUsesStoredCredentialsAndDatabaseClock() {
        var response = service.diagnoseDatabase(saved(false));
        verify(runtime).diagnoseConnection(eq(ProductTypeEnum.POSTGRESQL), isNull(),
                eq(stored.getJdbcUrl() + "?connectTimeout=5"), eq(stored.getUserName()), eq(TEST_PASSWORD),
                eq(new ConnectionDiagnosticOptions("public", false, 5000)));
        assertTrue(response.isConnectionSuccessful());
        assertEquals("PASS", response.getOverallStatus());
        assertEquals(STARTED, response.getCheckedAt());
        assertEquals(6, response.getGroups().size());
        assertEquals(17, response.getTotalCount());
        assertEquals(2, response.getNormalCount());
        assertEquals(1, writes.size());
        assertEquals(DatabaseConnectionStatusEnum.ONLINE.getStatus(), assignedStatus());
    }

    @Test
    void writeFailureDoesNotMarkSuccessfulConnectionOffline() {
        when(security.hasPermission("dataforge:database:update")).thenReturn(true);
        var checks = new ArrayList<>(success().checks());
        checks.add(check("WRITE", "INSERT", "FAIL"));
        when(runtime.diagnoseConnection(any(), any(), any(), any(), any(), any()))
                .thenReturn(new ConnectionDiagnosticResult("FAIL", true, 42, checks));
        var response = service.diagnoseDatabase(saved(true));
        assertEquals("FAIL", response.getOverallStatus());
        assertTrue(response.isConnectionSuccessful());
        assertEquals(1, response.getErrorCount());
        assertEquals(DatabaseConnectionStatusEnum.ONLINE.getStatus(), assignedStatus());
    }

    @Test
    void targetFailureMarksConnectionOfflineEvenWhenAuthenticationPassed() {
        when(runtime.diagnoseConnection(any(), any(), any(), any(), any(), any())).thenReturn(
                new ConnectionDiagnosticResult("FAIL", false, 42,
                        List.of(check("BASIC", "AUTH", "PASS"), check("TARGET", "VALIDATION", "PASS"),
                                check("TARGET", "SCHEMA", "FAIL"))));
        var response = service.diagnoseDatabase(saved(false));
        assertFalse(response.isConnectionSuccessful());
        assertEquals(DatabaseConnectionStatusEnum.OFFLINE.getStatus(), assignedStatus());
    }

    @Test
    void unsupportedNamespaceCannotBeRecordedAsOffline() {
        when(runtime.diagnoseConnection(any(), any(), any(), any(), any(), any())).thenReturn(
                new ConnectionDiagnosticResult("PASS", false, 42,
                        List.of(check("BASIC", "AUTH", "PASS"), check("TARGET", "VALIDATION", "PASS"),
                                check("TARGET", "SCHEMA", "SKIPPED"))));
        var response = service.diagnoseDatabase(saved(false));
        assertFalse(response.isConnectionSuccessful());
        assertEquals(DatabaseConnectionStatusEnum.UNTESTED.getStatus(), assignedStatus());
    }

    @Test
    void resolvedProductSelectsMongoCatalogAndPreservesConnectionStatus() {
        when(resolver.resolveProductType(any())).thenReturn(ProductTypeEnum.MONGODB);
        stored.setDatabaseSchema(null);
        var response = service.diagnoseDatabase(saved(false));
        assertEquals(List.of(4, 1, 2), response.getGroups().stream().map(DatabaseDiagnosisRespVO.Group::getTotalCount).toList());
        assertEquals(7, response.getTotalCount());
        assertEquals(DatabaseConnectionStatusEnum.ONLINE.getStatus(), assignedStatus());
    }

    @Test
    void hiddenCleanupFailureDoesNotPersistUnverifiedConnectionAsOffline() {
        when(resolver.resolveProductType(any())).thenReturn(ProductTypeEnum.MONGODB);
        stored.setDatabaseSchema(null);
        when(runtime.diagnoseConnection(any(), any(), any(), any(), any(), any())).thenReturn(
                new ConnectionDiagnosticResult("FAIL", false, 42,
                        List.of(check("BASIC", "AUTH", "PASS"), check("TARGET", "VALIDATION", "PASS"),
                                check("TARGET", "SCHEMA", "SKIPPED"), check("ADVANCED", "CLEANUP", "FAIL"))));
        var response = service.diagnoseDatabase(saved(false));
        assertEquals("FAIL", item(response, "CONFIG").getStatus());
        assertFalse(response.isConnectionSuccessful());
        assertEquals(DatabaseConnectionStatusEnum.UNTESTED.getStatus(), assignedStatus());
    }

    @Test
    void invalidDiagnosticResultCannotBeRecordedAsOffline() {
        when(runtime.diagnoseConnection(any(), any(), any(), any(), any(), any())).thenReturn(null);
        var response = service.diagnoseDatabase(saved(false));
        assertEquals("FAIL", response.getOverallStatus());
        assertFalse(response.isConnectionSuccessful());
        assertEquals(DatabaseConnectionStatusEnum.UNTESTED.getStatus(), assignedStatus());
    }

    @Test
    void unchangedFormCanReuseStoredPasswordAndIgnoresClientSecretKey() {
        DatabaseSaveReqVO connection = form();
        connection.setSecretKey("untrusted-client-key");
        connection.setJdbcUrl("");
        service.diagnoseDatabase(formRequest(connection));
        verify(runtime).diagnoseConnection(any(), any(), any(), any(), eq(TEST_PASSWORD), any());
        assertTrue(writes.isEmpty());
        verify(mapper, never()).selectDiagnosticStartTime();
    }

    @ParameterizedTest
    @ValueSource(strings = {"databaseType", "databaseIp", "databasePort", "databaseName",
            "databaseSchema", "userName", "jdbcUrl", "extendConfig"})
    void changedConnectionCannotSendSavedPasswordToAnotherTarget(String field) {
        DatabaseSaveReqVO connection = form();
        ReflectionTestUtils.setField(connection, field, "databaseType".equals(field) ? 2 : "changed");
        ServiceException error = assertThrows(ServiceException.class,
                () -> service.diagnoseDatabase(formRequest(connection)));
        assertEquals(DATABASE_CONNECT_FAILED.getCode(), error.getCode());
        verifyNoInteractions(runtime);
        assertTrue(writes.isEmpty());
    }

    @Test
    void suppliedPasswordUsesFormTargetWithoutChangingSavedStatus() {
        DatabaseSaveReqVO connection = form();
        connection.setDatabaseIp("another.example");
        connection.setJdbcUrl("jdbc:postgresql://another.example:5432/example");
        connection.setPassword("synthetic-new-password");
        service.diagnoseDatabase(formRequest(connection));
        verify(runtime).diagnoseConnection(any(), any(),
                eq(connection.getJdbcUrl() + "?connectTimeout=5"), any(), eq("synthetic-new-password"), any());
        verify(mapper, never()).selectById(anyLong());
        assertTrue(writes.isEmpty());
    }

    @Test
    void invalidSourceCannotReachDriverEvenWithoutBeanValidationProxy() {
        var request = saved(false);
        request.setConnection(form());
        assertThrows(ServiceException.class, () -> service.diagnoseDatabase(request));
        assertThrows(ServiceException.class, () -> service.diagnoseDatabase(new DatabaseDiagnosisReqVO()));
        verifyNoInteractions(runtime, mapper);
    }

    @Test
    void legacyBooleanTestDoesNotExposeDriverCredentialsOrConnectionUrl() {
        var metadata = mock(MetaDataByJdbcServiceImpl.class);
        when(runtime.jdbcMetadata(any())).thenReturn(metadata);
        doThrow(new IllegalStateException(stored.getJdbcUrl() + " password=" + TEST_PASSWORD))
                .when(metadata).testQuerySQL(any(), any(), any(), any());
        ServiceException error = assertThrows(ServiceException.class, () -> service.testDatabase(form()));
        assertEquals(DATABASE_CONNECT_FAILED.getCode(), error.getCode());
        assertFalse(error.getMessage().contains(TEST_PASSWORD));
        assertFalse(error.getMessage().contains(stored.getJdbcUrl()));
        assertTrue(error.getMessage().contains("分组诊断"));
    }

    @Test
    void persistenceGuardsRevisionCredentialsConfigurationAndNewerTest() {
        service.persistConnectionResult(stored, STARTED, DatabaseConnectionStatusEnum.ONLINE.getStatus(), "通过");
        String sql = writes.getFirst().getSqlSegment();
        for (String column : List.of("id", "update_time", "database_type", "database_ip",
                "database_port", "database_name", "database_schema", "user_name", "jdbc_url", "extend_config",
                "password", "secret_key", "status")) {
            assertTrue(sql.contains(column), column);
        }
        assertTrue(sql.contains("last_test_time IS NULL OR last_test_time <"));
        assertTrue(writes.getFirst().getParamNameValuePairs().containsValue(stored.getUpdateTime()));
        assertTrue(writes.getFirst().getParamNameValuePairs().containsValue(stored.getPassword()));
        assertTrue(writes.getFirst().getParamNameValuePairs().containsValue(stored.getSecretKey()));
        assertTrue(writes.getFirst().getParamNameValuePairs().containsValue(STARTED));
    }

    @Test
    void converterNeverTreatsAbsentOrUnknownResultsAsSuccessful() {
        var absent = DatabaseDiagnosisConverter.convert(null, STARTED);
        assertEquals("FAIL", absent.getOverallStatus());
        assertFalse(absent.isConnectionSuccessful());
        assertEquals(1, absent.getErrorCount());
        var unknown = DatabaseDiagnosisConverter.convert(
                new ConnectionDiagnosticResult("unknown", true, 0, List.of(check("BASIC", "AUTH", "unknown"))), STARTED);
        assertEquals("FAIL", unknown.getOverallStatus());
        assertFalse(unknown.isConnectionSuccessful());
        var unknownSummary = DatabaseDiagnosisConverter.convert(
                new ConnectionDiagnosticResult("unknown", true, 0, success().checks()), STARTED);
        assertEquals("FAIL", unknownSummary.getOverallStatus());
        assertFalse(unknownSummary.isConnectionSuccessful());
    }

    @Test
    void skippedChecksAreCountedWithoutFabricatingSuccessfulCapabilities() {
        var checks = List.of(check("WRITE", "TEMP_CREATE", "SKIPPED"), check("WRITE", "INSERT", "SKIPPED"));
        var skipped = DatabaseDiagnosisConverter.convert(new ConnectionDiagnosticResult("SKIPPED", false, 0, checks), STARTED);
        assertEquals("SKIPPED", skipped.getOverallStatus());
        assertEquals(17, skipped.getSkippedCount());
        assertEquals(0, skipped.getNormalCount());
        var mixed = new ArrayList<>(checks);
        mixed.add(check("BASIC", "AUTH", "PASS"));
        var response = DatabaseDiagnosisConverter.convert(new ConnectionDiagnosticResult("PASS", false, 0, mixed), STARTED);
        assertEquals("WARN", response.getOverallStatus());
        assertFalse(response.isConnectionSuccessful());
        assertEquals(17, response.getTotalCount());
    }

    @Test
    void inconsistentSummaryCannotBePromotedToPassAndDuplicateItemsAreRejected() {
        var inconsistent = DatabaseDiagnosisConverter.convert(
                new ConnectionDiagnosticResult("FAIL", true, 0, success().checks()), STARTED);
        assertEquals("FAIL", inconsistent.getOverallStatus());
        assertFalse(inconsistent.isConnectionSuccessful());
        assertEquals(1, inconsistent.getErrorCount());
        var checks = new ArrayList<>(success().checks());
        checks.add(check("BASIC", "AUTH", "PASS"));
        var duplicate = DatabaseDiagnosisConverter.convert(new ConnectionDiagnosticResult("PASS", true, 0, checks), STARTED);
        assertEquals("FAIL", duplicate.getOverallStatus());
        assertFalse(duplicate.isConnectionSuccessful());
        assertEquals(1, duplicate.getErrorCount());
        assertEquals(1, duplicate.getGroups().getFirst().getItems().stream()
                .filter(item -> "AUTH".equals(item.getCheckCode())).count());
    }

    @Test
    void atomicChecksProduceExactlySixGroupsAndSeventeenScreenshotItems() {
        var checks = new ArrayList<ConnectionDiagnosticResult.Check>();
        for (String key : List.of("CONFIG", "DNS", "TCP", "AUTH")) checks.add(check("BASIC", key, "PASS"));
        for (String key : List.of("VALIDATION", "SCHEMA", "PRIVILEGES")) checks.add(check("TARGET", key, "PASS"));
        for (String key : List.of("TABLES", "COLUMNS", "PRIMARY_KEYS", "EXTENDED_PROPERTIES")) checks.add(check("METADATA", key, "PASS"));
        checks.add(check("READ", "ZERO_ROW_READ", "PASS"));
        for (String key : List.of("TEMP_CREATE", "INSERT", "BATCH_INSERT", "UPDATE", "UPSERT", "MERGE", "DELETE", "TRUNCATE"))
            checks.add(check("WRITE", key, "PASS"));
        for (String key : List.of("TRANSACTION", "ALTER_TEMP", "RENAME_TEMP", "DROP_TEMP", "CLEANUP", "WRITE_CLEANUP", "CDC"))
            checks.add(check("ADVANCED", key, "PASS"));
        var response = DatabaseDiagnosisConverter.convert(new ConnectionDiagnosticResult("PASS", true, 27, checks), STARTED);
        assertEquals(List.of(4, 2, 3, 1, 2, 5), response.getGroups().stream().map(DatabaseDiagnosisRespVO.Group::getTotalCount).toList());
        assertEquals(List.of("基础连接", "目标资源访问", "元数据能力", "读取能力", "写入能力", "高级操作能力"),
                response.getGroups().stream().map(DatabaseDiagnosisRespVO.Group::getGroupName).toList());
        assertEquals(List.of("连接配置", "网络地址解析", "端口连通性", "账号认证", "目标资源访问", "账号角色与权限",
                        "元数据读取", "字段结构读取", "扩展属性读取", "库表读取权限", "INSERT写入", "批量写入",
                        "UPDATE/UPSERT/MERGE", "TRUNCATE/DELETE", "CREATE建表", "ALTER/RENAME/DROP", "CDC日志与复制权限"),
                response.getGroups().stream().flatMap(group -> group.getItems().stream()).map(DatabaseDiagnosisRespVO.Item::getCheckName).toList());
        assertEquals(17, response.getTotalCount());
        assertEquals(17, response.getNormalCount());
        assertTrue(response.isConnectionSuccessful());
        assertTrue(item(response, "TEMP_CREATE").getMessage().contains("不代表拥有普通业务表建表权限"));
        assertTrue(item(response, "CDC").getMessage().contains("不执行实际 CDC"));
        assertEquals("检查连接配置", item(response, "CONFIG").getCheckContent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PASS", "WARN", "FAIL", "SKIPPED"})
    void tableReferenceEvidenceIsOptionalReadEvidenceWithoutInvalidatingConnection(String status) {
        var checks = new ArrayList<>(success().checks());
        checks.add(check("METADATA", "COLUMNS", "PASS"));
        checks.add(check("METADATA", "PRIMARY_KEYS", "PASS"));
        checks.add(check("READ", "ZERO_ROW_READ", "PASS"));
        checks.add(check("ADVANCED", "CLEANUP", "PASS"));
        checks.add(check("READ", "TABLE_REFERENCE", status));
        String summary = "FAIL".equals(status) ? "FAIL" : "WARN".equals(status) ? "WARN" : "PASS";
        var conversion = DatabaseDiagnosisConverter.convertWithIntegrity(
                new ConnectionDiagnosticResult(summary, true, 9, checks), STARTED);
        assertTrue(conversion.validSource());
        assertTrue(conversion.response().isConnectionSuccessful());
        assertFalse(conversion.connectionFailed());
        assertEquals(17, conversion.response().getTotalCount());
        assertEquals("PASS", item(conversion.response(), "COLUMNS").getStatus());
        assertEquals("SKIPPED", item(conversion.response(), "CONFIG").getStatus());
        assertEquals("SKIPPED".equals(status) ? "WARN" : status,
                item(conversion.response(), "ZERO_ROW_READ").getStatus());
        assertTrue(item(conversion.response(), "ZERO_ROW_READ").getMessage().contains("TABLE_REFERENCE"));
        assertEquals("FAIL".equals(status) ? "FAIL" : "PASS".equals(status) ? "PASS" : "WARN",
                conversion.response().getOverallStatus());
        when(runtime.diagnoseConnection(any(), any(), any(), any(), any(), any()))
                .thenReturn(new ConnectionDiagnosticResult(summary, true, 9, checks));
        service.diagnoseDatabase(saved(false));
        assertEquals(DatabaseConnectionStatusEnum.ONLINE.getStatus(), assignedStatus());
    }

    @Test
    void legacyEvidenceWithoutTableReferenceDoesNotDowngradeReadOrColumns() {
        var checks = new ArrayList<>(success().checks());
        checks.add(check("METADATA", "COLUMNS", "PASS"));
        checks.add(check("METADATA", "PRIMARY_KEYS", "PASS"));
        checks.add(check("READ", "ZERO_ROW_READ", "PASS"));
        checks.add(check("ADVANCED", "CLEANUP", "PASS"));
        var conversion = DatabaseDiagnosisConverter.convertWithIntegrity(
                new ConnectionDiagnosticResult("PASS", true, 4, checks), STARTED);
        assertTrue(conversion.validSource());
        assertEquals("PASS", item(conversion.response(), "COLUMNS").getStatus());
        assertEquals("PASS", item(conversion.response(), "ZERO_ROW_READ").getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"duplicate", "wrongGroup"})
    void malformedTableReferenceEvidenceStillFailsIntegrity(String invalid) {
        var checks = new ArrayList<>(success().checks());
        checks.add(check("READ", "TABLE_REFERENCE", "PASS"));
        checks.add(check("duplicate".equals(invalid) ? "READ" : "METADATA", "TABLE_REFERENCE", "PASS"));
        if ("wrongGroup".equals(invalid)) {
            checks.remove(checks.size() - 2);
        }
        var conversion = DatabaseDiagnosisConverter.convertWithIntegrity(
                new ConnectionDiagnosticResult("PASS", true, 4, checks), STARTED);
        assertFalse(conversion.validSource());
        assertFalse(conversion.response().isConnectionSuccessful());
        assertEquals("FAIL", item(conversion.response(), "CONFIG").getStatus());
    }

    @Test
    void partiallySupportedCompositeIsWarningWithoutInvalidatingAtomicSummary() {
        var checks = new ArrayList<>(success().checks());
        checks.add(check("WRITE", "UPDATE", "PASS"));
        checks.add(check("WRITE", "UPSERT", "PASS"));
        checks.add(check("WRITE", "MERGE", "SKIPPED"));
        var conversion = DatabaseDiagnosisConverter.convertWithIntegrity(new ConnectionDiagnosticResult("PASS", true, 6, checks), STARTED);
        assertTrue(conversion.validSource());
        assertTrue(conversion.response().isConnectionSuccessful());
        assertEquals("WARN", item(conversion.response(), "UPDATE").getStatus());
        assertEquals("WARN", conversion.response().getOverallStatus());
        assertEquals("SKIPPED", item(conversion.response(), "CONFIG").getStatus());
    }

    @Test
    void cleanupProblemsRemainVisibleInsideTheSeventeenItems() {
        var checks = new ArrayList<>(success().checks());
        checks.add(check("READ", "ZERO_ROW_READ", "PASS"));
        checks.add(check("ADVANCED", "CLEANUP", "WARN"));
        for (String key : List.of("ALTER_TEMP", "RENAME_TEMP", "DROP_TEMP")) checks.add(check("ADVANCED", key, "PASS"));
        checks.add(check("ADVANCED", "WRITE_CLEANUP", "FAIL"));
        var response = DatabaseDiagnosisConverter.convert(new ConnectionDiagnosticResult("FAIL", true, 8, checks), STARTED);
        assertEquals(17, response.getTotalCount());
        assertEquals("WARN", item(response, "ZERO_ROW_READ").getStatus());
        assertEquals("FAIL", item(response, "ALTER_TEMP").getStatus());
        assertTrue(item(response, "ALTER_TEMP").getMessage().contains("WRITE_CLEANUP"));
        assertTrue(response.isConnectionSuccessful());
        assertTrue(response.getGroups().stream().flatMap(group -> group.getItems().stream())
                .noneMatch(item -> "CLEANUP".equals(item.getCheckCode()) || "WRITE_CLEANUP".equals(item.getCheckCode())));
    }

    @Test
    void unknownAtomicKeyFailsConfigurationIntegrityWithoutAddingAnEighteenthItem() {
        var checks = new ArrayList<>(success().checks());
        checks.add(check("ADVANCED", "UNRECOGNIZED", "PASS"));
        var conversion = DatabaseDiagnosisConverter.convertWithIntegrity(new ConnectionDiagnosticResult("PASS", true, 4, checks), STARTED);
        assertFalse(conversion.validSource());
        assertEquals(17, conversion.response().getTotalCount());
        assertEquals("FAIL", item(conversion.response(), "CONFIG").getStatus());
        assertFalse(conversion.response().isConnectionSuccessful());
    }

    @Test
    void elasticsearchDiagnosisRoutesToProductProbeWithPhysicalIndexAndLogicalNamespace() {
        elasticsearch("{\"schemaVersion\":1,\"index\":\"orders-2026\",\"timeoutSeconds\":7}");
        var response = service.diagnoseDatabase(saved(false));
        var request = org.mockito.ArgumentCaptor.forClass(cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionProbeRequest.class);
        verify(runtime).probeConnection(request.capture());
        verify(runtime, never()).diagnoseConnection(any(), any(), any(), any(), any(), any());
        assertEquals(ProductTypeEnum.ELASTICSEARCH, request.getValue().product());
        assertEquals(stored.getJdbcUrl(), request.getValue().jdbcUrl());
        assertEquals(stored.getUserName(), request.getValue().username());
        assertEquals(TEST_PASSWORD, request.getValue().password());
        assertEquals("es", request.getValue().database());
        assertEquals("es", request.getValue().schema());
        assertEquals("orders-2026", request.getValue().properties().get("index"));
        assertEquals(java.time.Duration.ofSeconds(7), request.getValue().timeout());
        assertFalse(request.getValue().toString().contains(TEST_PASSWORD));
        assertTrue(response.isConnectionSuccessful());
        assertEquals(DatabaseConnectionStatusEnum.ONLINE.getStatus(), assignedStatus());
    }

    @Test
    void elasticsearchLegacyTypeAliasStillReachesProductProbe() {
        elasticsearch("");
        when(types.selectById(1L)).thenReturn(DatabaseTypeDO.builder().id(1L).typeCode("ES").build());
        assertTrue(service.diagnoseDatabase(saved(false)).isConnectionSuccessful());
        verify(runtime).probeConnection(argThat(request -> request.product() == ProductTypeEnum.ELASTICSEARCH));
        assertFalse(ConnectionSettings.managed(types.selectById(1L)));
    }

    @Test
    void elasticsearchMissingNamespaceEvidenceCannotBecomeOnline() {
        elasticsearch("");
        when(runtime.probeConnection(any())).thenReturn(new ConnectionDiagnosticResult("PASS", true, 1,
                List.of(check("BASIC", "AUTH", "PASS"))));
        assertFalse(service.diagnoseDatabase(saved(false)).isConnectionSuccessful());
        assertEquals(DatabaseConnectionStatusEnum.UNTESTED.getStatus(), assignedStatus());
    }

    @Test
    void elasticsearchDiagnosisResolvesJdbcTemplateWithoutMutatingStoredSnapshot() {
        elasticsearch("");
        stored.setJdbcUrl("");
        service.diagnoseDatabase(saved(false));
        verify(runtime).probeConnection(argThat(request -> "jdbc:jest://localhost:5432".equals(request.jdbcUrl())));
        assertEquals("", stored.getJdbcUrl());
    }

    @Test
    void elasticsearchLegacyJdbcParametersRemainCompatibleWithoutInventingPhysicalIndex() {
        elasticsearch("connectTimeout=5");
        service.diagnoseDatabase(saved(false));
        verify(runtime).probeConnection(argThat(request -> request.jdbcUrl().equals(stored.getJdbcUrl() + "?connectTimeout=5")
                && !request.properties().containsKey("index") && "es".equals(request.database())));
        assertFalse(ConnectionSettings.managed(types.selectById(1L)));
    }

    @Test
    void elasticsearchIndexDenialRetainsEvidenceWithoutMarkingConnectionOffline() {
        elasticsearch("{\"schemaVersion\":1,\"index\":\"orders\"}");
        when(runtime.probeConnection(any())).thenReturn(new ConnectionDiagnosticResult("FAIL", true, 8,
                List.of(check("BASIC", "AUTH", "PASS"), check("TARGET", "SCHEMA", "PASS"),
                        check("METADATA", "INDEX_MAPPING", "PASS"),
                        new ConnectionDiagnosticResult.Check("TARGET", "INDEX_PRIVILEGES", "索引权限", "FAIL", 1,
                                "索引读取权限不足", "42501", 403, "UNVERIFIED"))));
        var response = service.diagnoseDatabase(saved(false));
        assertTrue(response.isConnectionSuccessful());
        assertEquals("FAIL", response.getOverallStatus());
        assertEquals("FAIL", item(response, "INDEX_PRIVILEGES").getStatus());
        assertEquals("UNVERIFIED", item(response, "INDEX_PRIVILEGES").getReasonCode());
        assertEquals(DatabaseConnectionStatusEnum.ONLINE.getStatus(), assignedStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAIL", "SKIPPED"})
    void elasticsearchRequiresSuccessfulLogicalNamespaceEvenIfProtocolConnected(String schemaStatus) {
        elasticsearch("");
        stored.setDatabaseName("invalid-logical-resource");
        when(runtime.probeConnection(any())).thenReturn(new ConnectionDiagnosticResult(
                "FAIL".equals(schemaStatus) ? "FAIL" : "PASS", true, 4,
                List.of(check("BASIC", "AUTH", "PASS"), check("TARGET", "SCHEMA", schemaStatus))));
        var response = service.diagnoseDatabase(saved(false));
        assertFalse(response.isConnectionSuccessful());
        assertEquals("FAIL".equals(schemaStatus) ? DatabaseConnectionStatusEnum.OFFLINE.getStatus()
                : DatabaseConnectionStatusEnum.UNTESTED.getStatus(), assignedStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "orders-*", "orders,users", "../orders", "_all", "orders?x", "orders%2fsecret", "Orders", ".", "-orders"})
    void elasticsearchRejectsUnsafeOrMultiplePhysicalIndicesBeforeProbe(String index) {
        elasticsearch("{\"schemaVersion\":1,\"index\":\"" + index + "\"}");
        var response = service.diagnoseDatabase(saved(false));
        assertFalse(response.isConnectionSuccessful());
        verify(runtime, never()).probeConnection(any());
        verify(runtime, never()).diagnoseConnection(any(), any(), any(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"schemaVersion\":1,\"password\":\"secret\"}",
            "{\"schemaVersion\":1,\"index\":123}", "{\"schemaVersion\":2,\"index\":\"orders\"}",
            "{\"schemaVersion\":1,\"jdbcParams\":\"index=orders\"}", "index=orders", ";index=orders", "%69ndex=orders"})
    void elasticsearchRejectsSecretOrMalformedDiagnosisConfiguration(String config) {
        elasticsearch(config);
        assertFalse(service.diagnoseDatabase(saved(false)).isConnectionSuccessful());
        verify(runtime, never()).probeConnection(any());
    }

    @Test
    void elasticsearchJsonKeepsJdbcParametersSeparateFromPhysicalIndex() {
        elasticsearch("{\"schemaVersion\":1,\"index\":\"orders\",\"jdbcParams\":\"connectTimeout=5\"}");
        service.diagnoseDatabase(saved(false));
        verify(runtime).probeConnection(argThat(request -> request.jdbcUrl().equals(stored.getJdbcUrl() + "?connectTimeout=5")
                && "orders".equals(request.properties().get("index"))));
    }

    @Test
    void elasticsearchFormDiagnosisDoesNotPersistAndRetainsStoredCredentialBoundary() {
        elasticsearch("{\"schemaVersion\":1,\"index\":\"orders\"}");
        service.diagnoseDatabase(formRequest(form()));
        verify(runtime).probeConnection(argThat(request -> TEST_PASSWORD.equals(request.password())));
        assertTrue(writes.isEmpty());
        verify(mapper, never()).selectDiagnosticStartTime();
        clearInvocations(runtime);
        var changed = form();
        changed.setExtendConfig("{\"schemaVersion\":1,\"index\":\"another\"}");
        assertThrows(ServiceException.class, () -> service.diagnoseDatabase(formRequest(changed)));
        verifyNoInteractions(runtime);
    }

    @Test
    void elasticsearchWriteAuthorizationAndSafeFailureRemainAtEntryBoundary() {
        elasticsearch("");
        assertThrows(ServiceException.class, () -> service.diagnoseDatabase(saved(true)));
        verifyNoInteractions(runtime);
        when(security.hasPermission("dataforge:database:update")).thenReturn(true);
        when(runtime.probeConnection(any())).thenThrow(new IllegalStateException("password=" + TEST_PASSWORD + stored.getJdbcUrl()));
        var response = service.diagnoseDatabase(saved(true));
        assertFalse(response.isConnectionSuccessful());
        assertFalse(response.toString().contains(TEST_PASSWORD));
        assertFalse(response.toString().contains(stored.getJdbcUrl()));
        verify(runtime, never()).diagnoseConnection(any(), any(), any(), any(), any(), any());
    }

    private void elasticsearch(String config) {
        when(types.selectById(1L)).thenReturn(DatabaseTypeDO.builder().id(1L).typeCode("ELASTICSEARCH")
                .urlTemplates("jdbc:jest://${host}:${port}").build());
        when(resolver.resolveProductType(any())).thenReturn(ProductTypeEnum.ELASTICSEARCH);
        stored.setJdbcUrl("jdbc:jest://localhost:9200");
        stored.setDatabaseName("es");
        stored.setDatabaseSchema("es");
        stored.setExtendConfig(config);
        when(runtime.probeConnection(any())).thenReturn(new ConnectionDiagnosticResult("PASS", true, 4,
                List.of(check("BASIC", "AUTH", "PASS"), check("TARGET", "SCHEMA", "PASS"))));
    }

    private static DatabaseDiagnosisRespVO.Item item(DatabaseDiagnosisRespVO response, String key) {
        return response.getGroups().stream().flatMap(group -> group.getItems().stream())
                .filter(item -> key.equals(item.getCheckCode())).findFirst().orElseThrow();
    }

    private static DatabaseDiagnosisReqVO saved(boolean write) {
        var request = new DatabaseDiagnosisReqVO();
        request.setDatabaseId(19L);
        request.setIncludeWriteChecks(write);
        return request;
    }

    private Integer assignedStatus() {
        var matcher = java.util.regex.Pattern.compile("connection_status=#[{]ew[.]paramNameValuePairs[.]([^}]+)}")
                .matcher(writes.getFirst().getSqlSet());
        assertTrue(matcher.find());
        return (Integer) writes.getFirst().getParamNameValuePairs().get(matcher.group(1));
    }

    private static DatabaseDiagnosisReqVO formRequest(DatabaseSaveReqVO connection) {
        var request = new DatabaseDiagnosisReqVO();
        request.setConnection(connection);
        return request;
    }

    private DatabaseSaveReqVO form() {
        var form = new DatabaseSaveReqVO();
        form.setId(stored.getId());
        form.setDatabaseType(stored.getDatabaseType());
        form.setDatabaseIp(stored.getDatabaseIp());
        form.setDatabasePort(stored.getDatabasePort());
        form.setDatabaseName(stored.getDatabaseName());
        form.setDatabaseSchema(stored.getDatabaseSchema());
        form.setJdbcUrl(stored.getJdbcUrl());
        form.setUserName(stored.getUserName());
        form.setExtendConfig(stored.getExtendConfig());
        return form;
    }

    private static ConnectionDiagnosticResult success() {
        return new ConnectionDiagnosticResult("PASS", true, 12,
                List.of(check("BASIC", "AUTH", "PASS"), check("TARGET", "VALIDATION", "PASS"), check("TARGET", "SCHEMA", "PASS")));
    }

    private static ConnectionDiagnosticResult.Check check(String group, String key, String status) {
        return new ConnectionDiagnosticResult.Check(group, key, key, status, 1, "组件安全文案", null, null);
    }
}
