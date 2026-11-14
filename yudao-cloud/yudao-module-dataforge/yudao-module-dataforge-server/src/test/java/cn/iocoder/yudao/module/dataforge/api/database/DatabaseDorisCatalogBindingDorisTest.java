package cn.iocoder.yudao.module.dataforge.api.database;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabasePreviewReqDTO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseQueryColumnsReqDTO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseQueryReqDTO;
import cn.iocoder.yudao.module.dataforge.service.access.AccessDatabaseResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 只使用显式提供的本机隔离 Doris；覆盖真实目录、投影、预览和表代次变化。 */
@Timeout(60)
@EnabledIfSystemProperty(named = "datahub.test.doris.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/")
class DatabaseDorisCatalogBindingDorisTest {
    private Connection control;
    private String schema;
    private DbSwitchRuntime runtime;
    private DatabaseApiImpl api;
    private AccessDatabaseResolver.ConnectionInfo info;
    private static final String LAYOUT = " DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1"
            + " PROPERTIES ('replication_num'='1')";

    @BeforeEach void createOwnedObjects() throws Exception {
        String url = System.getProperty("datahub.test.doris.url");
        control = DriverManager.getConnection(url, "root", "");
        schema = "consumer_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE DATABASE `" + schema + "`");
        sql("CREATE TABLE " + table() + "(id BIGINT, name VARCHAR(40), secret VARCHAR(40))" + LAYOUT);
        sql("INSERT INTO " + table() + " VALUES (9007199254740993,'reader','hidden')");
        info = new AccessDatabaseResolver.ConnectionInfo();
        info.setDatabaseId(7L); info.setProductType(ProductTypeEnum.DORIS); info.setSchema(schema);
        info.setConfigurationRevision("native-fixture-1");
        info.setJdbcUrl(url); info.setUsername("root"); info.setPassword("");
        var resolver = mock(AccessDatabaseResolver.class);
        when(resolver.resolve(eq(7L), any())).thenReturn(info);
        runtime = DbSwitchRuntime.standalone();
        api = new DatabaseApiImpl();
        ReflectionTestUtils.setField(api, "dbSwitchRuntime", runtime);
        ReflectionTestUtils.setField(api, "databaseResolver", resolver);
    }

    @AfterEach void removeOnlyOwnedObjects() throws Exception {
        if (runtime != null) runtime.close();
        if (control == null) return;
        try (var owned = control) {
            if (schema != null) {
                assertThat(schema).matches("consumer_[a-f0-9]{32}");
                sql("DROP DATABASE IF EXISTS `" + schema + "` FORCE");
            }
        }
    }

    private String table() { return "`" + schema + "`.`Orders`"; }
    private void sql(String text) throws Exception {
        try (var statement = control.createStatement()) {
            statement.setQueryTimeout(15);
            statement.execute(text);
        }
    }
    private DatabaseQueryReqDTO request() {
        var request = new DatabaseQueryReqDTO();
        request.setDatabaseId(7L); request.setSql("SELECT * FROM Orders");
        return request;
    }

    @Test void exactPickerColumnsScopedQueryAndPreviewReadTheInternalTable() {
        var tables = api.getQueryTableList(7L).getCheckedData();
        assertThat(tables).hasSize(1);
        var table = api.resolveQueryTable(7L, tables.getFirst().getTableReference()).getCheckedData();
        assertThat(table.getRelationObjectId()).matches("[0-9a-f]{64}");
        assertThat(api.getTableColumnList(7L, "Orders").getCheckedData())
                .extracting(cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseColumnRespDTO::getColumnName)
                .containsExactly("id", "name", "secret");
        var columns = new DatabaseQueryColumnsReqDTO();
        columns.setDatabaseId(7L); columns.setSchemaName(schema); columns.setTableName("Orders");
        columns.setExpectedObjectId(table.getRelationObjectId()); columns.setColumns(List.of("id", "name"));
        assertThat(api.getQueryTableColumns(columns).getCheckedData())
                .extracting(cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseColumnRespDTO::getColumnName)
                .containsExactly("id", "name");
        var request = request();
        var plan = api.parseRestrictedSelect(request).getCheckedData();
        request.setSql(plan.getNormalizedSql()); request.setExpectedObjectId(plan.getRelationObjectId());
        request.setExpectedSchema(schema); request.setExpectedTable("Orders"); request.setAllowedColumns(List.of("id"));
        var result = api.executeQuery(request).getCheckedData();
        assertThat(result.getRows()).hasSize(1);
        assertThat(result.getRows().getFirst()).containsOnlyKeys("id");
        assertThat(result.getRows().getFirst().get("id").toString()).isEqualTo("9007199254740993");
        var preview = new DatabasePreviewReqDTO();
        preview.setDatabaseId(7L); preview.setSchemaName(schema); preview.setTableName("Orders");
        preview.setColumns(List.of("id"));
        assertThat(api.previewTable(preview).getCheckedData().getRows().getFirst()).containsOnlyKeys("id");
    }

    @Test void truncateInvalidatesPriorExecutionAndMetadataEvidence() throws Exception {
        var request = request();
        var plan = api.parseRestrictedSelect(request).getCheckedData();
        request.setSql(plan.getNormalizedSql()); request.setExpectedObjectId(plan.getRelationObjectId());
        var columns = new DatabaseQueryColumnsReqDTO();
        columns.setDatabaseId(7L); columns.setSchemaName(schema); columns.setTableName("Orders");
        columns.setExpectedObjectId(plan.getRelationObjectId());
        sql("TRUNCATE TABLE " + table());
        assertThatThrownBy(() -> api.executeQuery(request)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> api.getQueryTableColumns(columns)).isInstanceOf(ServiceException.class);
        assertThat(api.parseRestrictedSelect(request()).getCheckedData().getRelationObjectId())
                .isNotEqualTo(plan.getRelationObjectId());
    }

    @Test void viewsCaseAliasesAndCatalogPrefixesRemainRejected() throws Exception {
        sql("CREATE VIEW `" + schema + "`.`OrderView` AS SELECT * FROM " + table());
        for (String reference : List.of("orders", "OrderView", "internal." + schema + ".Orders", "Orders o")) {
            assertThatThrownBy(() -> api.resolveQueryTable(7L, reference)).isInstanceOf(ServiceException.class);
        }
        assertThat(api.getQueryTableList(7L).getCheckedData()).hasSize(1);
    }

    @Test void boundStringFiltersAndNativeMetadataWorkAcrossSqlModes() throws Exception {
        String payload = "北区'\\?%";
        try (var insert = control.prepareStatement("INSERT INTO " + table() + " VALUES (2,?,'hidden')")) {
            insert.setQueryTimeout(15); insert.setString(1, payload); insert.executeUpdate();
        }
        for (String mode : List.of("ANSI_QUOTES", "NO_BACKSLASH_ESCAPES", "ANSI_QUOTES,NO_BACKSLASH_ESCAPES")) {
            info.setJdbcUrl(System.getProperty("datahub.test.doris.url") + schema
                    + "?useServerPrepStmts=false&emulateUnsupportedPstmts=true&sessionVariables="
                    + java.net.URLEncoder.encode("sql_mode='" + mode + "'", java.nio.charset.StandardCharsets.UTF_8));
            var bound = api.resolveQueryTable(7L, "Orders").getCheckedData();
            assertThat(bound.getRelationObjectId()).matches("[0-9a-f]{64}");
            assertThat(api.getTableColumnList(7L, "Orders").getCheckedData()).hasSize(3);
            var preview = new DatabasePreviewReqDTO();
            preview.setDatabaseId(7L); preview.setSchemaName(schema); preview.setTableName("Orders");
            preview.setColumns(List.of("id", "name"));
            var filter = new DatabasePreviewReqDTO.Filter();
            filter.setColumn("name"); filter.setOperator(DatabasePreviewReqDTO.Filter.OP_EQ); filter.setValue(payload);
            preview.setFilters(List.of(filter));
            var rows = api.previewTable(preview).getCheckedData().getRows();
            assertThat(rows).as(mode).hasSize(1);
            assertThat(rows.getFirst().get("name")).isEqualTo(payload);
        }
    }

    @Test void underscoreNamesRemainDistinctFromMatchingPatternNames() throws Exception {
        sql("CREATE TABLE `" + schema + "`.`order_lines`(id BIGINT)" + LAYOUT);
        sql("CREATE TABLE `" + schema + "`.`orderXlines`(id BIGINT)" + LAYOUT);
        var exact = api.resolveQueryTable(7L, "order_lines").getCheckedData();
        var other = api.resolveQueryTable(7L, "orderXlines").getCheckedData();
        assertThat(exact.getRelationObjectId()).isNotEqualTo(other.getRelationObjectId());
        assertThat(api.getQueryTableList(7L).getCheckedData()).hasSize(3);
    }

    @Test void preciseDorisValuesRemainStringsAndBooleanOneMatchesTrue() throws Exception {
        sql("CREATE TABLE `" + schema + "`.`precise_values`(id BIGINT, amount DECIMAL(30,8), wide_id LARGEINT, enabled BOOLEAN)"
                + LAYOUT);
        sql("INSERT INTO `" + schema + "`.`precise_values` VALUES (1,12345678901234567890.12345678,"
                + "170141183460469231731687303715884105726,true)");
        var preview = new DatabasePreviewReqDTO();
        preview.setDatabaseId(7L); preview.setSchemaName(schema); preview.setTableName("precise_values");
        var filter = new DatabasePreviewReqDTO.Filter();
        filter.setColumn("enabled"); filter.setOperator(DatabasePreviewReqDTO.Filter.OP_EQ); filter.setValue("1");
        preview.setFilters(List.of(filter));
        var rows = api.previewTable(preview).getCheckedData().getRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("amount")).isEqualTo("12345678901234567890.12345678");
        assertThat(rows.getFirst().get("wide_id")).isEqualTo("170141183460469231731687303715884105726");
    }
}
