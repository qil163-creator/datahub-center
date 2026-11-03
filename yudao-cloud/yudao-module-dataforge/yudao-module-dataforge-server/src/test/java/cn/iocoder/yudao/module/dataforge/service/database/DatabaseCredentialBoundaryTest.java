package cn.iocoder.yudao.module.dataforge.service.database;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.module.dataforge.controller.admin.database.DatabaseController;
import cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.DatabaseRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.DatabaseSaveReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.databasetype.DatabaseTypeMapper;
import cn.iocoder.yudao.module.dataforge.service.access.AccessDatabaseResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DatabaseCredentialBoundaryTest {
    private DatabaseServiceImpl service;
    private DbSwitchRuntime runtime;
    private MetaDataByJdbcServiceImpl metadata;
    private DatabaseDO stored;

    @BeforeEach void setup() {
        service = new DatabaseServiceImpl();
        var mapper = mock(DatabaseMapper.class);
        runtime = mock(DbSwitchRuntime.class); metadata = mock(MetaDataByJdbcServiceImpl.class);
        var resolver = mock(AccessDatabaseResolver.class);
        ReflectionTestUtils.setField(service, "databaseMapper", mapper);
        ReflectionTestUtils.setField(service, "databaseTypeMapper", mock(DatabaseTypeMapper.class));
        ReflectionTestUtils.setField(service, "dbSwitchRuntime", runtime);
        ReflectionTestUtils.setField(service, "accessDatabaseResolver", resolver);
        stored = DatabaseDO.builder().id(101L).databaseType(2).databaseIp("database.example")
                .databasePort("5432").databaseName("review").databaseSchema("public").userName("review_user")
                .jdbcUrl("jdbc:postgresql://database.example:5432/review?currentSchema=public")
                .secretKey("test-only-key").build();
        stored.setPassword(DatabasePasswordEncryptUtils.encrypt("sample-password", stored.getSecretKey()));
        when(mapper.selectById(101L)).thenReturn(stored);
        when(resolver.resolveProductType(any())).thenReturn(ProductTypeEnum.POSTGRESQL);
        when(runtime.jdbcMetadata(eq(ProductTypeEnum.POSTGRESQL))).thenReturn(metadata);
    }

    @Test void detailDoesNotRestorePlaintextAfterTheResponseWasSanitized() {
        var api = new DatabaseController(); var dependency = mock(DatabaseService.class);
        ReflectionTestUtils.setField(api, "databaseService", dependency);
        when(dependency.getDatabase(101L)).thenReturn(stored);
        var sanitized = new DatabaseRespVO(); sanitized.setId(101L);
        when(dependency.convertToRespList(anyList())).thenReturn(List.of(sanitized));
        var response = api.getDatabase(101L).getCheckedData();
        assertNull(response.getPassword()); assertNull(response.getSecretKey());
    }

    @Test void unchangedConnectionCanTestWithItsStoredPassword() {
        var request = request(); request.setPassword(null); request.setJdbcUrl(null);
        service.testDatabase(request);
        verify(metadata).testQuerySQL(stored.getJdbcUrl(), "review_user", "sample-password", ProductTypeEnum.POSTGRESQL.getTestSql());
    }

    @Test void changingAnyCredentialDestinationCannotReuseTheStoredPassword() {
        List<Consumer<DatabaseSaveReqVO>> changes = List.of(
                value -> value.setJdbcUrl("jdbc:postgresql://receiver.example:5432/review"),
                value -> value.setDatabaseIp("receiver.example"),
                value -> value.setDatabasePort("5433"),
                value -> value.setDatabaseName("other"),
                value -> value.setDatabaseSchema("other"),
                value -> value.setUserName("other_user"),
                value -> value.setDatabaseType(1),
                value -> value.setExtendConfig("sslmode=disable"));
        for (var change : changes) {
            var request = request(); change.accept(request);
            assertThrows(ServiceException.class, () -> service.testDatabase(request));
        }
        verifyNoInteractions(runtime, metadata);
    }

    @Test void explicitlyEnteredPasswordCanTestANewDestination() {
        var request = request(); request.setJdbcUrl("jdbc:postgresql://receiver.example:5432/review");
        request.setPassword("new-sample-password"); service.testDatabase(request);
        verify(metadata).testQuerySQL(request.getJdbcUrl(), "review_user", "new-sample-password", ProductTypeEnum.POSTGRESQL.getTestSql());
    }

    private DatabaseSaveReqVO request() {
        var request = BeanUtils.toBean(stored, DatabaseSaveReqVO.class); request.setPassword(null);
        return request;
    }
}
