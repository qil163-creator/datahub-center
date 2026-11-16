package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.service.GuardedMutationResult;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterModelDO;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterColumnMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.*;

import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.MASTER_MODEL_DATABASE_NOT_EXISTS;
import static cn.iocoder.yudao.module.governance.service.master.MasterDataQueryFilterTest.column;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 经业务服务与真实 DB Switch：相似匹配全表读取，以及合并“更新保留记录 + 删除被合并记录”的单事务条件写。 */
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[a-z][a-z0-9_]*")
@Timeout(30)
class MasterDataMergeWritePostgreSqlTest {

    private Connection control;
    private String schema;
    private DbSwitchRuntime runtime;
    private MasterDataServiceImpl service;

    @BeforeEach
    void setup() throws Exception {
        String url = System.getProperty("datahub.test.postgres.url");
        String user = System.getProperty("datahub.test.postgres.user", "codex_test");
        control = DriverManager.getConnection(url, user, "");
        schema = "merge_write_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE SCHEMA " + schema);
        sql("CREATE TABLE " + schema + ".company (id BIGINT PRIMARY KEY, name TEXT NOT NULL, phone TEXT)");
        sql("INSERT INTO " + schema + ".company VALUES (3,'腾讯',NULL),(1,'华为','0755'),(2,'华为技术',NULL)");
        runtime = DbSwitchRuntime.standalone();
        var models = mock(MasterModelService.class);
        var columns = mock(MasterColumnMapper.class);
        var resolver = mock(MasterDatabaseResolver.class);
        when(models.validateModelReleased(10L)).thenReturn(
                MasterModelDO.builder().id(10L).databaseId(20L).tableName("company").build());
        when(columns.selectListByModelId(10L)).thenReturn(List.of(column("id", ColumnMetaData.TYPE_INTEGER, 1),
                column("name", ColumnMetaData.TYPE_STRING, 0), column("phone", ColumnMetaData.TYPE_STRING, 0)));
        var info = new MasterDatabaseResolver.ConnectionInfo();
        info.setProductType(ProductTypeEnum.POSTGRESQL);
        info.setSchema(schema);
        info.setJdbcUrl(url);
        info.setUsername(user);
        info.setPassword("");
        when(resolver.resolve(20L, MASTER_MODEL_DATABASE_NOT_EXISTS)).thenReturn(info);
        when(resolver.createDataSource(any())).thenAnswer(call -> {
            var source = new HikariDataSource();
            source.setJdbcUrl(url);
            source.setUsername(user);
            source.setPassword("");
            source.setMaximumPoolSize(1);
            return source;
        });
        service = new MasterDataServiceImpl();
        ReflectionTestUtils.setField(service, "modelService", models);
        ReflectionTestUtils.setField(service, "columnMapper", columns);
        ReflectionTestUtils.setField(service, "databaseResolver", resolver);
        ReflectionTestUtils.setField(service, "dbSwitchRuntime", runtime);
    }

    @AfterEach
    void cleanup() throws Exception {
        try {
            if (runtime != null) {
                runtime.close();
            }
        } finally {
            if (control != null) {
                try {
                    if (schema != null && schema.matches("merge_write_[a-f0-9]{32}")) {
                        sql("DROP SCHEMA " + schema + " CASCADE");
                    }
                } finally {
                    control.close();
                }
            }
        }
    }

    @Test
    void scanReadsInPrimaryKeyOrderAndReportsTotalBeyondLimit() {
        var all = service.scanRows(10L, 10);
        assertEquals(3, all.total());
        assertEquals(List.of("1", "2", "3"), all.rows().stream().map(row -> row.get("id")).toList(),
                "长整数主键按接口传输形式输出为字符串");
        var limited = service.scanRows(10L, 2);
        assertEquals(2, limited.rows().size());
        assertEquals(3, limited.total(), "超过上限时总数仍为全表记录数，供调用方拒绝");
    }

    @Test
    void mergeUpdatesSurvivorAndDeletesDuplicateAtomically() throws Exception {
        Map<String, Object> survivor = row("1", "华为", "0755");
        Map<String, Object> duplicate = row("2", "华为技术", null);
        Map<String, Object> golden = row("1", "华为技术", "0755");

        var stale = service.applyGuardedChange(10L, "MERGE", List.of(survivor, row("2", "已被改动", null)),
                List.of(golden, Map.of("id", "2")));
        assertEquals(GuardedMutationResult.Status.NOT_APPLIED, stale.status());
        assertEquals(GuardedMutationResult.Failure.CONFLICT, stale.failure());
        assertEquals("华为", name(1), "被合并记录已变化时保留记录的更新一并回滚");
        assertEquals("华为技术", name(2));

        var applied = service.applyGuardedChange(10L, "MERGE", List.of(survivor, duplicate),
                List.of(golden, Map.of("id", "2")));
        assertEquals(GuardedMutationResult.Status.APPLIED, applied.status());
        assertEquals("华为技术", name(1));
        assertNull(name(2));
        assertEquals("腾讯", name(3));
    }

    private static Map<String, Object> row(String id, String name, String phone) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("name", name);
        row.put("phone", phone);
        return row;
    }

    private String name(long id) throws Exception {
        try (var statement = control.createStatement();
             ResultSet rows = statement.executeQuery("SELECT name FROM " + schema + ".company WHERE id=" + id)) {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    private void sql(String query) throws Exception {
        try (var statement = control.createStatement()) {
            statement.execute(query);
        }
    }
}
