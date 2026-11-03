package cn.iocoder.yudao.module.dataforge.service.access;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.module.dataforge.controller.admin.access.vo.DataAccessSaveReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.access.DataAccessMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DataAccessHookWireContractTest {
    @Test void saveAndDetailKeepAllFourOptionalHooksInTheCurrentJsonVersion() throws Exception {
        var f = new Fixture(ProductTypeEnum.POSTGRESQL);
        var mapper = new tools.jackson.databind.ObjectMapper();
        var request = mapper.readValue("{\"taskName\":\"hook config\",\"engineType\":1,\"taskType\":2,\"sourceType\":1,"
                + "\"sourceDatabaseId\":11,\"targetDatabaseId\":12,\"sourceSelectedTables\":[\"rows\"],"
                + "\"sourceBeforeSqlScripts\":\"SELECT 1\",\"sourceAfterSqlScripts\":\"SELECT 2\","
                + "\"targetBeforeSqlScripts\":\"SELECT 3\",\"targetAfterSqlScripts\":\"SELECT 4\"}", DataAccessSaveReqVO.class);
        f.service.createDataAccess(request);
        var captured = ArgumentCaptor.forClass(DataAccessDO.class); verify(f.tasks).insert(captured.capture());
        var saved = captured.getValue(); when(f.tasks.selectById(4L)).thenReturn(saved);
        var response = f.service.getDataAccess(4L);
        assertEquals("SELECT 1", response.getSourceBeforeSqlScripts()); assertEquals("SELECT 2", response.getSourceAfterSqlScripts());
        assertEquals("SELECT 3", response.getTargetBeforeSqlScripts()); assertEquals("SELECT 4", response.getTargetAfterSqlScripts());
        assertEquals(1, response.getSchemaVersion());
        assertEquals("SELECT 4", mapper.readTree(mapper.writeValueAsString(response)).get("targetAfterSqlScripts").asText());
    }

    @ParameterizedTest
    @EnumSource(value = ProductTypeEnum.class, names = {"MONGODB", "ELASTICSEARCH", "INFLUXDB3"})
    void nonSqlProductsCannotPersistAnApparentSqlHook(ProductTypeEnum product) {
        var f = new Fixture(product); var request = request(); request.setTargetBeforeSqlScripts("SELECT 1");
        assertThrows(RuntimeException.class, () -> f.service.createDataAccess(request));
        verify(f.tasks, never()).insert(any(DataAccessDO.class));
    }

    @Test void otherExecutionEnginesCannotSilentlyDiscardHookConfiguration() {
        var f = new Fixture(ProductTypeEnum.POSTGRESQL); var request = request();
        request.setEngineType(3); request.setSourceAfterSqlScripts("SELECT 1");
        assertThrows(RuntimeException.class, () -> f.service.createDataAccess(request));
        verify(f.tasks, never()).insert(any(DataAccessDO.class));
    }

    private static DataAccessSaveReqVO request() {
        var request = new DataAccessSaveReqVO(); request.setTaskName("hooks"); request.setTaskType(2); request.setSourceType(1);
        request.setSourceDatabaseId(11L); request.setTargetDatabaseId(12L); request.setSourceSelectedTables(List.of("rows"));
        return request;
    }

    private static class Fixture {
        final DataAccessMapper tasks = mock(DataAccessMapper.class);
        final DataAccessServiceImpl service = new DataAccessServiceImpl();
        Fixture(ProductTypeEnum product) {
            var databases = mock(DatabaseMapper.class); var resolver = mock(AccessDatabaseResolver.class);
            var source = DatabaseDO.builder().id(11L).name("source").build(); var target = DatabaseDO.builder().id(12L).name("target").build();
            when(databases.selectById(11L)).thenReturn(source); when(databases.selectById(12L)).thenReturn(target);
            when(databases.selectByIds(anyCollection())).thenReturn(List.of(source, target));
            when(resolver.resolveProductType(any(DatabaseDO.class))).thenReturn(product);
            when(tasks.insert(any(DataAccessDO.class))).thenAnswer(call -> { DataAccessDO saved = call.getArgument(0); saved.setId(4L); return 1; });
            ReflectionTestUtils.setField(service, "dataAccessMapper", tasks); ReflectionTestUtils.setField(service, "databaseMapper", databases);
            ReflectionTestUtils.setField(service, "databaseResolver", resolver);
            ReflectionTestUtils.setField(service, "deptApi", mock(cn.iocoder.yudao.module.system.api.dept.DeptApi.class));
            ReflectionTestUtils.setField(service, "adminUserApi", mock(cn.iocoder.yudao.module.system.api.user.AdminUserApi.class));
        }
    }
}
