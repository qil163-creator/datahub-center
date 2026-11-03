package cn.iocoder.yudao.module.dataforge.service.database;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.DatabaseRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.DatabaseSaveReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.databasetype.vo.DatabaseTypeRespVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.databasetype.DatabaseTypeDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.databasetype.DatabaseTypeMapper;
import cn.iocoder.yudao.module.dataforge.enums.database.DatabaseConnectionStatusEnum;
import cn.iocoder.yudao.module.dataforge.service.databasetype.DatabaseTypeCapabilityService;
import cn.iocoder.yudao.module.system.api.dept.DeptApi;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseServiceImplTest {

    private DatabaseMapper databaseMapper;
    private DatabaseTypeMapper databaseTypeMapper;
    private DeptApi deptApi;
    private AdminUserApi adminUserApi;
    private DatabaseTypeCapabilityService typeCapabilityService;
    private DatabaseServiceImpl databaseService;
    private final List<LambdaUpdateWrapper<DatabaseDO>> writes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "database-test"),
                DatabaseDO.class);
        databaseMapper = mock(DatabaseMapper.class);
        databaseTypeMapper = mock(DatabaseTypeMapper.class);
        deptApi = mock(DeptApi.class);
        adminUserApi = mock(AdminUserApi.class);
        typeCapabilityService = mock(DatabaseTypeCapabilityService.class);
        databaseService = new DatabaseServiceImpl();
        ReflectionTestUtils.setField(databaseService, "databaseMapper", databaseMapper);
        ReflectionTestUtils.setField(databaseService, "databaseTypeMapper", databaseTypeMapper);
        ReflectionTestUtils.setField(databaseService, "deptApi", deptApi);
        ReflectionTestUtils.setField(databaseService, "adminUserApi", adminUserApi);
        ReflectionTestUtils.setField(databaseService, "typeCapabilityService", typeCapabilityService);
        writes.clear();
        when(databaseMapper.update(isNull(), any())).thenAnswer(invocation -> {
            LambdaUpdateWrapper<DatabaseDO> wrapper = invocation.getArgument(1);
            wrapper.getSqlSet();
            wrapper.getSqlSegment();
            writes.add(wrapper);
            return 1;
        });
    }

    @ParameterizedTest
    @CsvSource({"79", "80"})
    void hdfsCreateAndUpdateNeverPersistPasswordsFromTheFormOrPreviousProtocol(int previousType) {
        when(databaseTypeMapper.selectById(80L)).thenReturn(DatabaseTypeDO.builder()
                .id(80L).typeCode("HDFS").build());
        var request = new DatabaseSaveReqVO();
        request.setDatabaseType(80);
        request.setDatabaseIp("hdfs.example");
        request.setDatabasePort("9870");
        request.setUserName("synthetic");
        request.setExtendConfig("{\"webHdfsUrl\":\"http://hdfs.example:9870\"}");
        request.setPassword("synthetic-unused-secret");

        databaseService.createDatabase(request);
        var created = ArgumentCaptor.forClass(DatabaseDO.class);
        verify(databaseMapper).insert(created.capture());
        assertEquals("", created.getValue().getPassword());

        request.setId(7L);
        request.setPassword("");
        when(databaseMapper.selectById(7L)).thenReturn(DatabaseDO.builder()
                .id(7L).databaseType(previousType).password("synthetic-old-encrypted-value").secretKey("synthetic-key").build());
        databaseService.updateDatabase(request);
        var updated = ArgumentCaptor.forClass(DatabaseDO.class);
        verify(databaseMapper).updateById(updated.capture());
        assertEquals("", updated.getValue().getPassword());
    }

    @ParameterizedTest
    @CsvSource({"FTP,21", "POSTGRESQL,5432"})
    void passwordBasedConnectionsKeepStoredCiphertextWhenUpdatePasswordIsBlank(String type, String port) {
        when(databaseTypeMapper.selectById(80L)).thenReturn(DatabaseTypeDO.builder().id(80L).typeCode(type).build());
        String ciphertext = DatabasePasswordEncryptUtils.encrypt("synthetic-secret", "synthetic-key");
        when(databaseMapper.selectById(7L)).thenReturn(DatabaseDO.builder()
                .id(7L).databaseType(80).databaseIp("source.example").databasePort(port)
                .userName("synthetic").password(ciphertext).secretKey("synthetic-key").build());
        var request = new DatabaseSaveReqVO();
        request.setId(7L); request.setDatabaseType(80); request.setDatabaseIp("source.example");
        request.setDatabasePort(port); request.setUserName("synthetic"); request.setPassword("");

        databaseService.updateDatabase(request);

        var updated = ArgumentCaptor.forClass(DatabaseDO.class);
        verify(databaseMapper).updateById(updated.capture());
        assertEquals(ciphertext, updated.getValue().getPassword());
    }

    @Test
    void connectionAddressRequiresHostAndPortExceptForSqliteFileUrl() {
        when(databaseTypeMapper.selectById(23L)).thenReturn(DatabaseTypeDO.builder()
                .id(23L).typeCode("SQLITE3").build());
        when(databaseTypeMapper.selectById(2L)).thenReturn(DatabaseTypeDO.builder()
                .id(2L).typeCode("POSTGRESQL").build());
        var request = new DatabaseSaveReqVO();
        request.setDatabaseType(23);
        request.setJdbcUrl("jdbc:sqlite:/data/review.db");
        databaseService.createDatabase(request);

        request.setJdbcUrl("jdbc:sqlite:");
        assertTrue(assertThrows(ServiceException.class, () -> databaseService.createDatabase(request))
                .getMessage().contains("SQLite"));
        verify(databaseMapper, times(1)).insert(any(DatabaseDO.class));

        request.setDatabaseType(2);
        request.setJdbcUrl("jdbc:postgresql://db.example:5432/review");
        assertThrows(ServiceException.class, () -> databaseService.createDatabase(request));
        request.setDatabaseIp("db.example");
        assertThrows(ServiceException.class, () -> databaseService.createDatabase(request));
        verify(databaseMapper, times(1)).insert(any(DatabaseDO.class));
        request.setDatabasePort("5432");
        databaseService.createDatabase(request);
        verify(databaseMapper, times(2)).insert(any(DatabaseDO.class));
    }

    @Test
    void createAndUpdateSqliteConnectionDefaultMissingAddressToEmptyStrings() {
        when(databaseTypeMapper.selectById(23L)).thenReturn(DatabaseTypeDO.builder()
                .id(23L).typeCode("SQLITE3").urlTemplates("jdbc:sqlite:${dbName}").build());
        var request = new DatabaseSaveReqVO();
        request.setDatabaseType(23);
        request.setDatabaseName("/data/review.db");
        request.setJdbcUrl("jdbc:sqlite:/data/review.db");

        databaseService.createDatabase(request);

        var created = ArgumentCaptor.forClass(DatabaseDO.class);
        verify(databaseMapper).insert(created.capture());
        assertEquals("", created.getValue().getDatabaseIp());
        assertEquals("", created.getValue().getDatabasePort());
        assertEquals("jdbc:sqlite:/data/review.db", created.getValue().getJdbcUrl());

        request.setId(7L);
        when(databaseMapper.selectById(7L)).thenReturn(DatabaseDO.builder()
                .id(7L).databaseType(23).jdbcUrl("jdbc:sqlite:/data/review.db").build());
        databaseService.updateDatabase(request);

        var updated = ArgumentCaptor.forClass(DatabaseDO.class);
        verify(databaseMapper).updateById(updated.capture());
        assertEquals("", updated.getValue().getDatabaseIp());
        assertEquals("", updated.getValue().getDatabasePort());
    }

    @Test
    void networkConnectionUpdateRejectsMissingAddressWithoutMapperWrites() {
        when(databaseTypeMapper.selectById(2L)).thenReturn(DatabaseTypeDO.builder()
                .id(2L).typeCode("POSTGRESQL").build());
        when(databaseMapper.selectById(7L)).thenReturn(DatabaseDO.builder()
                .id(7L).databaseType(2).databaseIp("db.example").databasePort("5432")
                .jdbcUrl("jdbc:postgresql://db.example:5432/review").build());
        var request = new DatabaseSaveReqVO();
        request.setId(7L);
        request.setDatabaseType(2);
        request.setJdbcUrl("jdbc:postgresql://db.example:5432/review");

        assertThrows(ServiceException.class, () -> databaseService.updateDatabase(request));
        verify(databaseMapper, never()).updateById(any(DatabaseDO.class));
        assertTrue(writes.isEmpty());
    }

    @ParameterizedTest
    @CsvSource({
            "jdbc:oracle:thin:@${host}:${port}:${sid},jdbc:oracle:thin:@db.example:1521:review,false",
            "jdbc:oracle:thin:@//${host}:${port}/${serviceName},jdbc:oracle:thin:@//db.example:1521/review,false",
            "jdbc:oracle:thin:@${host}:${port}:${sid},jdbc:oracle:thin:@db.example:1521:review,true",
            "jdbc:oracle:thin:@//${host}:${port}/${serviceName},jdbc:oracle:thin:@//db.example:1521/review,true"
    })
    void createDatabaseResolvesOracleDatabaseNameFromTemplateOrSubmittedUrl(
            String template, String expected, boolean submittedUrl) {
        when(databaseTypeMapper.selectById(3L)).thenReturn(DatabaseTypeDO.builder()
                .id(3L).typeCode("ORACLE").urlTemplates(template).build());
        var request = new DatabaseSaveReqVO();
        request.setDatabaseType(3);
        request.setDatabaseIp("db.example");
        request.setDatabasePort("1521");
        request.setDatabaseName("review");
        if (submittedUrl) request.setJdbcUrl(template);

        databaseService.createDatabase(request);

        var saved = ArgumentCaptor.forClass(DatabaseDO.class);
        verify(databaseMapper).insert(saved.capture());
        assertEquals(expected, saved.getValue().getJdbcUrl());
    }

    @Test
    void simpleListUsesComponentCursorDirectionsWithoutInferringProductFromTheDriver() {
        try (var runtime = cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime.standalone()) {
            ReflectionTestUtils.setField(databaseService, "dbSwitchRuntime", runtime);
            var gbase = DatabaseDO.builder().id(9007199254740993L).databaseType(1).name("gbase source").build();
            var unknown = DatabaseDO.builder().id(2L).databaseType(2).name("unregistered source").build();
            when(databaseMapper.selectList(any())).thenReturn(List.of(gbase, unknown));
            when(databaseTypeMapper.selectByIds(anyCollection())).thenReturn(List.of(
                    DatabaseTypeDO.builder().id(1L).typeCode("GBASE").typeName("GBase 8a").build(),
                    DatabaseTypeDO.builder().id(2L).typeCode("unknown-product").build()));
            var list = databaseService.getSimpleDatabaseList(null);
            assertEquals(gbase.getId(), list.getFirst().getId());
            assertEquals("GBASE8A", list.getFirst().getProductId());
            assertEquals(List.of("GBASE8A", "POSTGRESQL"), list.getFirst().getCursorTargets());
            assertNull(list.get(1).getProductId());
            assertTrue(list.get(1).getCursorTargets().isEmpty());
        }
    }

    @Test
    void simpleListPreservesExactOracleTemplatesAndUnresolvedConnections() {
        try (var runtime = cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime.standalone()) {
            ReflectionTestUtils.setField(databaseService, "dbSwitchRuntime", runtime);
            when(databaseMapper.selectList(any())).thenReturn(List.of(
                    DatabaseDO.builder().id(11L).databaseType(3).name("SID connection").build(),
                    DatabaseDO.builder().id(12L).databaseType(4).name("Service connection").build(),
                    DatabaseDO.builder().id(13L).databaseType(99).name("Missing type connection").build()));
            when(databaseTypeMapper.selectByIds(anyCollection())).thenReturn(List.of(
                    DatabaseTypeDO.builder().id(3L).typeCode("ORACLE").typeName("Oracle SID")
                            .categoryCode("RELATIONAL").iconPath("/oracle-sid.svg").build(),
                    DatabaseTypeDO.builder().id(4L).typeCode("ORACLE").typeName("Oracle Service")
                            .categoryCode("RELATIONAL").iconPath("/oracle-service.svg").build()));

            var list = databaseService.getSimpleDatabaseList(null);

            assertEquals(3, list.size());
            assertEquals(List.of(3L, 4L, 99L), list.stream().map(item -> item.getDatabaseType()).toList());
            assertEquals("Oracle SID", list.getFirst().getDatabaseTypeName());
            assertEquals("Oracle Service", list.get(1).getDatabaseTypeName());
            for (var item : list.subList(0, 2)) {
                assertEquals("ORACLE", item.getProductId());
                assertEquals("RELATIONAL", item.getCategoryCode());
            }
            assertEquals("/oracle-service.svg", list.get(1).getIconPath());
            assertEquals(13L, list.get(2).getId());
            assertNull(list.get(2).getDatabaseTypeName());
            assertNull(list.get(2).getCategoryCode());
            assertNull(list.get(2).getProductId());
        }
    }

    @Test
    void convertToRespListFillsTypeCodeAndUserNicknames() {
        DatabaseDO database = DatabaseDO.builder()
                .id(4L)
                .name("DWS")
                .databaseType(3)
                .password("secret")
                .secretKey("key")
                .connectionStatus(DatabaseConnectionStatusEnum.ONLINE.getStatus())
                .lastTestMsg("ok")
                .build();
        database.setCreator("1");
        database.setUpdater("2");

        DatabaseTypeDO type = DatabaseTypeDO.builder()
                .id(3L)
                .typeCode("DORIS")
                .typeName("Doris 数据库")
                .iconPath("/icon/doris.svg")
                .build();
        when(databaseTypeMapper.selectByIds(anyCollection())).thenReturn(List.of(type));
        var capability = new DatabaseTypeRespVO();
        capability.setProductId("DORIS");
        capability.setCapabilities(Map.of("CONNECT", new DatabaseTypeRespVO.Capability("DECLARED", "测试组件契约")));
        when(typeCapabilityService.describe(type)).thenReturn(capability);
        when(deptApi.getDeptMap(anyCollection())).thenReturn(Map.of());
        when(adminUserApi.getUserMap(anyCollection())).thenReturn(Map.of(
                1L, user(1L, "管理员"),
                2L, user(2L, "李四")));

        List<DatabaseRespVO> result = databaseService.convertToRespList(List.of(database));

        assertEquals(1, result.size());
        DatabaseRespVO vo = result.get(0);
        assertEquals("DORIS", vo.getTypeCode());
        assertEquals("Doris 数据库", vo.getDatabaseTypeName());
        assertEquals("管理员", vo.getCreatorName());
        assertEquals("李四", vo.getUpdaterName());
        assertNull(vo.getPassword());
        assertNull(vo.getSecretKey());
        assertTrue(vo.isHasPassword());
        assertEquals("DORIS", vo.getProductId());
        assertEquals("DECLARED", vo.getCapabilities().get("CONNECT").status());
        assertEquals(DatabaseConnectionStatusEnum.ONLINE.getStatus(), vo.getConnectionStatus());
        assertEquals("ok", vo.getLastTestMsg());
        database.setPassword("");
        assertFalse(databaseService.convertToRespList(List.of(database)).getFirst().isHasPassword());
    }

    @Test
    void persistConnectionResultWritesStatusAndTruncatesMessage() {
        databaseService.persistConnectionResult(
                DatabaseDO.builder().id(6L).build(), LocalDateTime.of(2026, 10, 15, 10, 0),
                DatabaseConnectionStatusEnum.OFFLINE.getStatus(), "x".repeat(600));

        assertEquals(1, writes.size());
        String sqlSet = writes.getFirst().getSqlSet();
        assertTrue(sqlSet.contains("connection_status"));
        assertTrue(sqlSet.contains("last_test_time"));
        assertTrue(sqlSet.contains("last_test_msg"));
        assertTrue(writes.getFirst().getSqlSegment().contains("id"));
        assertTrue(writes.getFirst().getParamNameValuePairs().values().stream()
                .anyMatch(value -> value instanceof String text && text.length() == 512));
    }

    @Test
    void resetConnectionStatusClearsProbeResult() {
        databaseService.resetConnectionStatus(6L);

        assertEquals(1, writes.size());
        String sqlSet = writes.getFirst().getSqlSet();
        assertTrue(sqlSet.contains("connection_status"));
        assertTrue(sqlSet.contains("last_test_time"));
        assertTrue(sqlSet.contains("last_test_msg"));
        assertTrue(writes.getFirst().getParamNameValuePairs()
                .containsValue(DatabaseConnectionStatusEnum.UNTESTED.getStatus()));
    }

    @Test
    void connectionConfigChangeIgnoresBlankEqualsAndDetectsHostOrPassword() {
        DatabaseDO oldObj = DatabaseDO.builder()
                .databaseType(7)
                .databaseIp("127.0.0.1")
                .databasePort("9030")
                .databaseName("ods")
                .databaseSchema("")
                .userName("root")
                .jdbcUrl("jdbc:mysql://127.0.0.1:9030/ods")
                .extendConfig(null)
                .build();
        DatabaseDO same = DatabaseDO.builder()
                .databaseType(7)
                .databaseIp("127.0.0.1")
                .databasePort("9030")
                .databaseName("ods")
                .databaseSchema(null)
                .userName("root")
                .jdbcUrl("jdbc:mysql://127.0.0.1:9030/ods")
                .extendConfig("")
                .build();
        DatabaseDO moved = DatabaseDO.builder()
                .databaseType(7)
                .databaseIp("10.0.0.1")
                .databasePort("9030")
                .databaseName("ods")
                .jdbcUrl("jdbc:mysql://10.0.0.1:9030/ods")
                .userName("root")
                .build();

        assertFalse(DatabaseServiceImpl.isConnectionConfigChanged(oldObj, same, false));
        assertTrue(DatabaseServiceImpl.isConnectionConfigChanged(oldObj, moved, false));
        assertTrue(DatabaseServiceImpl.isConnectionConfigChanged(oldObj, same, true));
    }

    private static AdminUserRespDTO user(Long id, String nickname) {
        AdminUserRespDTO dto = new AdminUserRespDTO();
        dto.setId(id);
        dto.setNickname(nickname);
        return dto;
    }
}
