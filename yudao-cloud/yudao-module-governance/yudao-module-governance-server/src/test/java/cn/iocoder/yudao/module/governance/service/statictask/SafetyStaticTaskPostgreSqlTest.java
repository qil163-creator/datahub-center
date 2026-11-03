package cn.iocoder.yudao.module.governance.service.statictask;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseColumnRespDTO;
import cn.iocoder.yudao.module.governance.controller.admin.statictask.vo.SafetyStaticTaskFieldRuleVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.statictask.SafetyStaticTaskDO;
import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import cn.iocoder.yudao.module.governance.service.rule.SafetyMaskingSpec;
import cn.iocoder.yudao.module.governance.service.rule.SafetyRuleService;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 仅操作一次性容器内隔离表，真实验证物理同表身份及全量覆盖事务回滚。 */
@Testcontainers(disabledWithoutDocker = true)
class SafetyStaticTaskPostgreSqlTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final String SCHEMA = "static_mask_fixture";
    private static final String READER_PASSWORD = UUID.randomUUID().toString();
    private static final String WRITER_PASSWORD = UUID.randomUUID().toString();
    private SafetyStaticTaskExecutor executor;
    private DbSwitchRuntime runtime;

    @BeforeAll
    static void roles() throws Exception {
        try (var connection = admin(); var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE static_mask_reader LOGIN PASSWORD '" + READER_PASSWORD + "'");
            statement.execute("CREATE ROLE static_mask_writer LOGIN PASSWORD '" + WRITER_PASSWORD + "'");
        }
    }

    @BeforeEach
    void setup() throws Exception {
        try (var connection = admin(); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            statement.execute("CREATE SCHEMA " + SCHEMA);
            statement.execute("CREATE TABLE " + SCHEMA + ".source_rows(mobile varchar(40) NOT NULL)");
            statement.execute("INSERT INTO " + SCHEMA + ".source_rows VALUES ('x'), ('xx')");
            statement.execute("CREATE TABLE " + SCHEMA + ".target_rows(mobile varchar(40) NOT NULL CHECK(mobile <> '**'))");
            statement.execute("INSERT INTO " + SCHEMA + ".target_rows VALUES ('keep')");
            statement.execute("GRANT USAGE ON SCHEMA " + SCHEMA + " TO static_mask_reader, static_mask_writer");
            statement.execute("GRANT ALL ON ALL TABLES IN SCHEMA " + SCHEMA + " TO static_mask_reader, static_mask_writer");
        }
        executor = new SafetyStaticTaskExecutor();
        runtime = DbSwitchRuntime.standalone();
        ReflectionTestUtils.setField(executor, "dbSwitchRuntime", runtime);
        var resolver = mock(MasterDatabaseResolver.class);
        var source = info(1L, "static_mask_reader", READER_PASSWORD);
        var target = info(2L, "static_mask_writer", WRITER_PASSWORD);
        when(resolver.resolve(eq(1L), any())).thenReturn(source);
        when(resolver.resolve(eq(2L), any())).thenReturn(target);
        when(resolver.createDataSource(any())).thenAnswer(call -> dataSource(call.getArgument(0)));
        ReflectionTestUtils.setField(executor, "databaseResolver", resolver);
        var api = mock(DatabaseApi.class);
        var column = new DatabaseColumnRespDTO(); column.setColumnName("mobile");
        when(api.getTableColumnList(eq(1L), anyString())).thenReturn(CommonResult.success(List.of(column)));
        ReflectionTestUtils.setField(executor, "databaseApi", api);
        var rules = mock(SafetyRuleService.class);
        var spec = SafetyMaskingSpec.builder().ruleId(1L).methodCode("MASK")
                .config(Map.of()).replaceItems(List.of()).build();
        when(rules.resolveUsableMaskingSpec(1L)).thenReturn(spec);
        ReflectionTestUtils.setField(executor, "ruleService", rules);
    }

    @AfterEach
    void closeRuntime() { runtime.close(); }

    @Test
    void twoPlatformRecordsAndDifferentLoginUsersCannotOverwriteTheirSharedPhysicalSource() throws Exception {
        var task = task(); task.setTargetTableName("source_rows");
        assertThatThrownBy(() -> run(task)).isInstanceOf(ServiceException.class);
        assertThat(values("source_rows")).containsExactly("x", "xx");
    }

    @Test
    void targetConstraintFailureRestoresExistingRowsAcrossAllBatches() throws Exception {
        assertThatThrownBy(() -> run(task())).isInstanceOf(Exception.class);
        assertThat(values("target_rows")).containsExactly("keep");
        assertThat(values("source_rows")).containsExactly("x", "xx");
    }

    @Test
    void sourceOpeningFailureNeverClearsExistingTargetRows() throws Exception {
        var task = task(); task.setSourceTableName("missing_source_rows");
        assertThatThrownBy(() -> run(task)).isInstanceOf(Exception.class);
        assertThat(values("target_rows")).containsExactly("keep");
    }

    @Test
    void inheritedChildCannotBeReadWhileItsParentIsOverwritten() throws Exception {
        try (var connection = admin(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + SCHEMA + ".parent_rows(mobile varchar(40) NOT NULL)");
            statement.execute("CREATE TABLE " + SCHEMA + ".child_rows() INHERITS (" + SCHEMA + ".parent_rows)");
            statement.execute("INSERT INTO " + SCHEMA + ".parent_rows VALUES ('parent_keep')");
            statement.execute("INSERT INTO " + SCHEMA + ".child_rows VALUES ('child_keep'), ('child_second')");
            statement.execute("GRANT ALL ON " + SCHEMA + ".parent_rows, " + SCHEMA
                    + ".child_rows TO static_mask_reader, static_mask_writer");
        }
        var task = task(); task.setSourceTableName("child_rows"); task.setTargetTableName("parent_rows");
        assertThatThrownBy(() -> run(task)).isInstanceOf(ServiceException.class);
        // ONLY 分别核对父表和子表自身的数据，避免继承查询掩盖父表或子表被改写。
        assertThat(values("parent_rows", true)).containsExactly("parent_keep");
        assertThat(values("child_rows", true)).containsExactly("child_keep", "child_second");
        assertThat(values("parent_rows")).containsExactly("child_keep", "child_second", "parent_keep");
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPEND", "OVERWRITE"})
    void targetTriggerWritingBackToTheSourceIsRejectedBeforeAnyRowsChange(String writeMode) throws Exception {
        try (var connection = admin(); var statement = connection.createStatement()) {
            // 移除原约束失败因素，让该测试只由用户触发器门禁决定是否能执行。
            statement.execute("DROP TABLE " + SCHEMA + ".target_rows");
            statement.execute("CREATE TABLE " + SCHEMA + ".target_rows(mobile varchar(40) NOT NULL)");
            statement.execute("INSERT INTO " + SCHEMA + ".target_rows VALUES ('keep')");
            statement.execute("GRANT ALL ON " + SCHEMA + ".target_rows TO static_mask_reader, static_mask_writer");
            statement.execute("CREATE FUNCTION " + SCHEMA + ".write_source_effect() RETURNS trigger LANGUAGE plpgsql AS $fixture$ "
                    + "BEGIN INSERT INTO " + SCHEMA + ".source_rows VALUES ('trigger_effect'); RETURN NULL; END; $fixture$");
            statement.execute("CREATE TRIGGER static_mask_write_source AFTER INSERT OR DELETE ON " + SCHEMA
                    + ".target_rows FOR EACH ROW EXECUTE FUNCTION " + SCHEMA + ".write_source_effect()");
        }
        var task = task(); task.setWriteMode(writeMode);
        assertThatThrownBy(() -> run(task)).isInstanceOf(ServiceException.class);
        assertThat(values("target_rows")).containsExactly("keep");
        assertThat(values("source_rows")).containsExactly("x", "xx");
    }

    @Test
    void incomingCascadeForeignKeyCannotDeleteTheSourceDuringOverwrite() throws Exception {
        try (var connection = admin(); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE " + SCHEMA + ".target_rows");
            statement.execute("CREATE TABLE " + SCHEMA + ".target_rows(mobile varchar(40) PRIMARY KEY)");
            statement.execute("INSERT INTO " + SCHEMA + ".target_rows VALUES ('x'), ('xx')");
            statement.execute("GRANT ALL ON " + SCHEMA + ".target_rows TO static_mask_reader, static_mask_writer");
            statement.execute("ALTER TABLE " + SCHEMA + ".source_rows ADD CONSTRAINT source_target_fk FOREIGN KEY(mobile) "
                    + "REFERENCES " + SCHEMA + ".target_rows(mobile) ON DELETE CASCADE");
        }
        assertThatThrownBy(() -> run(task())).isInstanceOf(ServiceException.class);
        // 外键位于源表，目标上的内部参照完整性触发器同样必须拒绝。
        assertThat(values("target_rows")).containsExactly("x", "xx");
        assertThat(values("source_rows")).containsExactly("x", "xx");
    }

    @Test
    void inheritedParentSourceCannotAppendIntoItsOwnChild() throws Exception {
        try (var connection = admin(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + SCHEMA + ".parent_rows(mobile varchar(40) NOT NULL)");
            statement.execute("CREATE TABLE " + SCHEMA + ".child_rows() INHERITS (" + SCHEMA + ".parent_rows)");
            statement.execute("INSERT INTO " + SCHEMA + ".parent_rows VALUES ('parent_keep')");
            statement.execute("INSERT INTO " + SCHEMA + ".child_rows VALUES ('child_keep')");
            statement.execute("GRANT ALL ON " + SCHEMA + ".parent_rows, " + SCHEMA
                    + ".child_rows TO static_mask_reader, static_mask_writer");
        }
        var task = task(); task.setSourceTableName("parent_rows"); task.setTargetTableName("child_rows"); task.setWriteMode("APPEND");
        assertThatThrownBy(() -> run(task)).isInstanceOf(ServiceException.class);
        assertThat(values("parent_rows", true)).containsExactly("parent_keep");
        assertThat(values("child_rows", true)).containsExactly("child_keep");
    }

    @Test
    void ordinaryAppendCommitsAllBatchesAndPreservesExistingTargetRows() throws Exception {
        try (var connection = admin(); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE " + SCHEMA + ".target_rows");
            statement.execute("CREATE TABLE " + SCHEMA + ".target_rows(mobile varchar(40) NOT NULL)");
            statement.execute("INSERT INTO " + SCHEMA + ".target_rows VALUES ('keep')");
            statement.execute("GRANT ALL ON " + SCHEMA + ".target_rows TO static_mask_reader, static_mask_writer");
        }
        var task = task(); task.setWriteMode("APPEND");
        assertThat(run(task)).isEqualTo(2L);
        assertThat(values("target_rows")).containsExactlyInAnyOrder("keep", "*", "**");
        assertThat(values("source_rows")).containsExactly("x", "xx");
    }

    @Test
    void sourceViewReadingTheTargetCannotOverwriteItsUnderlyingProductionTable() throws Exception {
        try (var connection = admin(); var statement = connection.createStatement()) {
            statement.execute("CREATE VIEW " + SCHEMA + ".source_alias AS SELECT mobile FROM " + SCHEMA + ".target_rows");
            statement.execute("GRANT SELECT ON " + SCHEMA + ".source_alias TO static_mask_reader, static_mask_writer");
        }
        var task = task(); task.setSourceTableName("source_alias");
        assertThatThrownBy(() -> run(task)).isInstanceOf(ServiceException.class);
        // 视图名称不同不代表不同生产关系，必须同时保留视图和底层表的原始数据。
        assertThat(values("source_alias")).containsExactly("keep");
        assertThat(values("target_rows")).containsExactly("keep");
    }

    private Long run(SafetyStaticTaskDO task) {
        var rule = new SafetyStaticTaskFieldRuleVO(); rule.setColumnName("mobile"); rule.setRuleId(1L);
        return ReflectionTestUtils.invokeMethod(executor, "doExecute", task, List.of(rule));
    }
    private static SafetyStaticTaskDO task() {
        return SafetyStaticTaskDO.builder().sourceDatabaseId(1L).targetDatabaseId(2L)
                .sourceTableName("source_rows").targetTableName("target_rows").writeMode("OVERWRITE").batchSize(1).build();
    }
    private static MasterDatabaseResolver.ConnectionInfo info(Long id, String user, String password) {
        var info = new MasterDatabaseResolver.ConnectionInfo(); info.setDatabaseId(id);
        info.setSchema(SCHEMA); info.setProductType(ProductTypeEnum.POSTGRESQL);
        info.setJdbcUrl(POSTGRES.getJdbcUrl()); info.setUsername(user); info.setPassword(password); return info;
    }
    private static HikariDataSource dataSource(MasterDatabaseResolver.ConnectionInfo info) {
        var source = new HikariDataSource(); source.setJdbcUrl(info.getJdbcUrl());
        source.setUsername(info.getUsername()); source.setPassword(info.getPassword());
        source.setMaximumPoolSize(2); source.setMinimumIdle(0); source.setConnectionTimeout(5000); return source;
    }
    private static Connection admin() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
    private static List<String> values(String table) throws Exception {
        return values(table, false);
    }
    private static List<String> values(String table, boolean only) throws Exception {
        try (var connection = admin(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT mobile FROM " + (only ? "ONLY " : "") + SCHEMA + "." + table + " ORDER BY mobile")) {
            var values = new ArrayList<String>(); while (rows.next()) values.add(rows.getString(1)); return values;
        }
    }
}
