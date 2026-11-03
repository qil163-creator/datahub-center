package cn.iocoder.yudao.module.supply.service.dataapi;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.module.governance.api.security.SafetySecurityApi;
import cn.iocoder.yudao.module.supply.api.app.SupplyGrantCommandCodec;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO.Binding;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO.Scope;
import cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo.DataApiExecuteRespVO;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiDO;
import cn.iocoder.yudao.module.supply.dal.mysql.app.SupplyAppMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.appapi.AppApiMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.appapi.SupplyGrantSourceMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.dataapi.DataApiMapper;
import cn.iocoder.yudao.module.supply.framework.config.SupplyDataApiProperties;
import cn.iocoder.yudao.module.supply.service.app.SharingPostgresSupport;
import cn.iocoder.yudao.module.supply.service.app.grant.EffectiveGrantSnapshot;
import cn.iocoder.yudao.module.supply.service.app.grant.SupplyGrantReadService;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * PostgreSQL 授权读取 → 生产执行器集成；主体/治理 RPC mock，取数使用同一临时库的 public.people。
 * 每例使用 SharingPostgresSupport 的独立临时库、原始前置 fixture 及 migration 0041。
 * 不模拟授权快照、mapper、数据库时钟或返回前复验，也不启动自己的容器。
 */
@EnabledIfEnvironmentVariable(named = "SHARING_TEST_JDBC_URL", matches = ".+")
class DataApiGrantRuntimePostgresTest extends SharingPostgresSupport {

    private static final long TENANT = 1L;
    private static final long APP_ID = 1L;
    private static final long API_ID = 10L;
    private static final long USER_ID = 7L;
    private static final int SCOPE_CHANGED = 1_500_042_010;
    private static final int NOT_EFFECTIVE = 1_500_042_009;

    private SupplyGrantReadService reader;
    private DataApiMapper apis;
    private SupplyApiRedisStore redis;
    private RecordingExecutor executor;
    private final List<String> executedSql = new ArrayList<>();
    private CheckedAction duringQuery = () -> { };

    @BeforeEach
    void prepareRuntime() throws Exception {
        try (var input = getClass().getResourceAsStream("/sql/sharing-coordinator-prerequisites.sql")) {
            if (input == null) throw new IllegalStateException("Missing coordinator test fixture");
            sql(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        migrate("0041_supply_grant-source-protocol.sql");
        migrate("0095_supply_etl-batch-consumption.sql");
        migrate("0169_datahub_remove-business-tenancy.sql");
        // 只补临时最小旧表中 BaseMapper 读取所需列，不修改平台 schema 或原 fixture。
        sql("""
                ALTER TABLE dh_supply_app_api
                  ADD COLUMN start_date date, ADD COLUMN end_date date,
                  ADD COLUMN description text, ADD COLUMN permission_user_id bigint,
                  ADD COLUMN remark text, ADD COLUMN source_id varchar(256), ADD COLUMN purpose text,
                  ADD COLUMN valid_from timestamp, ADD COLUMN valid_to timestamp,
                  ADD COLUMN approved_contract_hash varchar(64), ADD COLUMN api_version varchar(128),
                  ADD COLUMN creator varchar(64), ADD COLUMN updater varchar(64),
                  ADD COLUMN create_time timestamp;
                ALTER TABLE dh_supply_api
                  ADD COLUMN category_id bigint, ADD COLUMN name varchar(100),
                  ADD COLUMN path varchar(100), ADD COLUMN method varchar(16),
                  ADD COLUMN result_format varchar(16), ADD COLUMN description text,
                  ADD COLUMN ip_whitelist text, ADD COLUMN ip_rule_type varchar(16),
                  ADD COLUMN limit_enabled int, ADD COLUMN limit_seconds int, ADD COLUMN limit_count int,
                  ADD COLUMN auth_type varchar(16), ADD COLUMN cache_enabled int DEFAULT 1,
                  ADD COLUMN cache_seconds int DEFAULT 60, ADD COLUMN breaker_enabled int,
                  ADD COLUMN breaker_error_count int, ADD COLUMN breaker_window_seconds int,
                  ADD COLUMN breaker_open_seconds int, ADD COLUMN alarm_enabled int,
                  ADD COLUMN sql_text text, ADD COLUMN forward_url text, ADD COLUMN forward_method varchar(16),
                  ADD COLUMN request_params jsonb, ADD COLUMN response_fields jsonb,
                  ADD COLUMN remark text, ADD COLUMN provider_dept_id bigint, ADD COLUMN resource_ref text,
                  ADD COLUMN owner_user_id bigint, ADD COLUMN draft_revision int, ADD COLUMN draft_json jsonb,
                  ADD COLUMN row_version int DEFAULT 1, ADD COLUMN creator varchar(64),
                  ADD COLUMN updater varchar(64), ADD COLUMN create_time timestamp, ADD COLUMN update_time timestamp;
                INSERT INTO dh_supply_app_api(app_id,api_id,protocol_version,
                  aggregate_revision,migration_status,grant_status)
                  VALUES(1,10,2,3,'CONFIRMED',3);
                CREATE TABLE public.people(id bigint PRIMARY KEY, name text, phone text);
                INSERT INTO public.people(id,name,phone) VALUES(1,'synthetic-name','synthetic-phone');
                """);

        var datasource = new UnpooledDataSource("org.postgresql.Driver",
                jdbcUrl + "?currentSchema=datahub-center&socketTimeout=15&connectTimeout=5", jdbcUser, "");
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true); // 保留默认 SESSION 缓存，验证真实短事务读取。
        for (var mapper : List.of(SupplyAppMapper.class, AppApiMapper.class,
                SupplyGrantSourceMapper.class, DataApiMapper.class)) {
            configuration.addMapper(mapper);
        }
        configuration.setEnvironment(new Environment("grant-runtime", new SpringManagedTransactionFactory(), datasource));
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        var apps = session.getMapper(SupplyAppMapper.class);
        var aggregates = session.getMapper(AppApiMapper.class);
        var sources = session.getMapper(SupplyGrantSourceMapper.class);
        apis = session.getMapper(DataApiMapper.class);
        var users = mock(AdminUserApi.class);
        var user = new AdminUserRespDTO();
        user.setId(USER_ID);
        user.setStatus(0);
        when(users.getUser(USER_ID)).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .as("subject RPC must not hold the local grant transaction").isFalse();
            return CommonResult.success(user);
        });
        // 生产 reader 本身使用 TransactionTemplate；不需要模拟代理或手写事务读取器。
        reader = new SupplyGrantReadService(apps, aggregates, sources, apis, users,
                new DataSourceTransactionManager(datasource));
        var initial = runtime();
        initial.setContractHash(DataApiContract.contractHash(initial));
        try (var statement = connection.prepareStatement("UPDATE dh_supply_api SET contract_hash=? WHERE id=?")) {
            statement.setString(1, initial.getContractHash());
            statement.setLong(2, API_ID);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
        source(connection, "A", initial, List.of("id", "name"), 0, null);
        source(connection, "B", initial, List.of("id", "phone"), 0, null);

        executor = new RecordingExecutor();
        redis = mock(SupplyApiRedisStore.class);
        var resolver = mock(SupplyDatabaseResolver.class);
        var database = new SupplyDatabaseResolver.ConnectionInfo();
        database.setDatabaseId(9L);
        database.setProductType(ProductTypeEnum.POSTGRESQL);
        when(resolver.resolve(anyLong())).thenReturn(database);
        var security = mock(SafetySecurityApi.class);
        when(security.getMaskExempt(USER_ID, "QUERY")).thenReturn(CommonResult.success(null));
        var properties = new SupplyDataApiProperties();
        properties.setResultCacheEnabled(true);
        ReflectionTestUtils.setField(executor, "grantReadService", reader);
        ReflectionTestUtils.setField(executor, "appApiMapper", aggregates);
        ReflectionTestUtils.setField(executor, "supplyAppMapper", apps);
        ReflectionTestUtils.setField(executor, "databaseResolver", resolver);
        ReflectionTestUtils.setField(executor, "safetySecurityApi", security);
        ReflectionTestUtils.setField(executor, "redisStore", redis);
        ReflectionTestUtils.setField(executor, "dataApiProperties", properties);
    }


    @Test
    void currentRuntimeProjectsTheRealUnionAndNeverUsesTheResultCache() {
        DataApiDO current = runtime();
        EffectiveGrantSnapshot snapshot = snapshot();
        assertThat(snapshot.contractHash()).isEqualTo(DataApiContract.contractHash(current));
        assertThat(snapshot.approvedColumns()).containsExactly("id", "name", "phone");
        assertThat(execute(current).getRows()).singleElement().satisfies(row ->
                assertThat(row).containsOnlyKeys("id", "name", "phone").containsEntry("id", 1L));
        assertThat(executedSql).hasSize(1);
        assertThat(executedSql.getFirst()).contains("\"id\"", "\"name\"", "\"phone\"").doesNotContain("*");
        verifyNoInteractions(redis);
    }

    @Test
    void oldRuntimeIsRejectedEvenWhenTheNewContractAndNewApprovalAreCurrentlyValid() throws Exception {
        DataApiDO old = runtime();
        publishAndApproveNewContract();
        DataApiDO current = runtime();
        EffectiveGrantSnapshot newGrant = snapshot();
        assertThat(newGrant.contractHash()).isEqualTo(current.getContractHash()).isNotEqualTo(old.getContractHash());
        assertThat(newGrant.contractHash()).isEqualTo(DataApiContract.contractHash(current));
        assertThat(newGrant.apiControlVersion()).isEqualTo(current.getRowVersion());

        assertRejectedWithoutQuery(old, SCOPE_CHANGED);
        // 不自动替换旧请求；显式重新获取的运行配置才能成功，且执行新的隐藏字段规则。
        assertThat(execute(current).getRows().getFirst()).containsKey("id").doesNotContainKey("name");
        assertThat(executedSql).hasSize(1);
        assertThat(old.getResponseFieldsJson()).isNull();
        verifyNoInteractions(redis);
    }

    @Test
    void sameContractWithNewControlVersionStillRejectsTheOldRuntime() throws Exception {
        DataApiDO old = runtime();
        changeIndependently("UPDATE dh_supply_api SET row_version=row_version+1, active_release_id=101 WHERE id=10");
        assertThat(snapshot().contractHash()).isEqualTo(old.getContractHash());
        assertThat(snapshot().apiControlVersion()).isEqualTo(old.getRowVersion() + 1);
        assertRejectedWithoutQuery(old, SCOPE_CHANGED);
        assertThat(execute(runtime()).getRows()).hasSize(1);
        assertThat(executedSql).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"responseFields", "requestParams", "resourceRef", "contractHash"})
    void authoritativeSnapshotDoesNotAuthorizeAlteredRuntimeMaterial(String field) {
        DataApiDO altered = runtime();
        assertThat(snapshot().contractHash()).isEqualTo(altered.getContractHash());
        switch (field) {
            case "responseFields" -> altered.setResponseFieldsJson("[{\"name\":\"id\"}]");
            case "requestParams" -> altered.setRequestParamsJson("[]");
            case "resourceRef" -> altered.setResourceRef("unapproved-resource");
            case "contractHash" -> altered.setContractHash("unapproved-contract");
            default -> throw new AssertionError(field);
        }
        assertRejectedWithoutQuery(altered, SCOPE_CHANGED);
    }

    @Test
    void revokingOneSourceNarrowsTheNextQueryWithoutRetainingItsExclusiveColumn() throws Exception {
        DataApiDO current = runtime();
        execute(current);
        assertThat(executedSql.getFirst()).contains("\"name\"", "\"phone\"");
        changeIndependently("UPDATE dh_supply_grant_source SET source_status='REVOKED',"
                + "source_revision=source_revision+1 WHERE source_key='A';"
                + "UPDATE dh_supply_app_api SET aggregate_revision=aggregate_revision+1");
        assertThat(snapshot().approvedColumns()).containsExactly("id", "phone");
        assertThat(execute(current).getRows()).singleElement().satisfies(row ->
                assertThat(row).containsOnlyKeys("id", "phone"));
        assertThat(executedSql).hasSize(2);
        assertThat(executedSql.getLast()).contains("\"id\"", "\"phone\"").doesNotContain("name", "*");
        verifyNoInteractions(redis);
    }

    @Test
    void databaseExpiryBoundaryRemovesOnlyTheExpiredSourceFields() throws Exception {
        long boundary = databaseNow();
        replaceValidity("A", 0, boundary);
        EffectiveGrantSnapshot grant = snapshot();
        assertThat(grant.evaluatedAt().toEpochMilli()).isGreaterThanOrEqualTo(boundary);
        assertThat(grant.approvedColumns()).containsExactly("id", "phone");
        assertThat(execute(runtime()).getRows()).singleElement().satisfies(row ->
                assertThat(row).containsOnlyKeys("id", "phone"));
        assertThat(executedSql).hasSize(1);
        assertThat(executedSql.getFirst()).contains("\"id\"", "\"phone\"").doesNotContain("name", "*");
    }

    @Test
    void noSourceAtOrAfterItsEndBoundaryRejectsBeforeQuery() throws Exception {
        long boundary = databaseNow();
        replaceValidity("A", 0, boundary);
        replaceValidity("B", 0, boundary);
        assertRejectedWithoutQuery(runtime(), NOT_EFFECTIVE);
    }

    @Test
    void futureSourceCannotContributeUntilTheDatabaseStartBoundary() throws Exception {
        replaceValidity("A", databaseNow() + TimeUnit.DAYS.toMillis(1), null);
        execute(runtime());
        assertThat(executedSql.getFirst()).contains("\"phone\"").doesNotContain("name");
        long boundary = databaseNow();
        replaceValidity("A", boundary, null);
        assertThat(snapshot().evaluatedAt().toEpochMilli()).isGreaterThanOrEqualTo(boundary);
        execute(runtime());
        assertThat(executedSql.getLast()).contains("\"name\"", "\"phone\"");
        assertThat(executedSql).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE dh_supply_grant_source SET source_status='REVOKED',source_revision=source_revision+1 WHERE source_key='A'",
            "UPDATE dh_supply_grant_source SET source_status='SUSPENDED',source_revision=source_revision+1 WHERE source_key='A'",
            "UPDATE dh_supply_app_api SET aggregate_disabled=true,aggregate_revision=aggregate_revision+1",
            "UPDATE dh_supply_app SET app_status=0 WHERE id=1",
            "UPDATE dh_supply_api SET status=0 WHERE id=10",
            "UPDATE dh_supply_api SET row_version=row_version+1 WHERE id=10",
            "UPDATE dh_supply_api SET contract_hash='changed-contract' WHERE id=10"})
    void independentlyCommittedChangeDuringQueryDiscardsResultsWithoutRetry(String change) {
        duringQuery = () -> changeIndependently(change);
        assertCode(() -> execute(runtime()), SCOPE_CHANGED);
        assertThat(executedSql).as("query ran once but no response or retry is allowed").hasSize(1);
        verifyNoInteractions(redis);
    }

    @Test
    void replacementContractWithValidNewSourcesStillDiscardsTheAlreadyRunningQuery() {
        DataApiDO old = runtime();
        duringQuery = this::publishAndApproveNewContract;
        assertCode(() -> execute(old), SCOPE_CHANGED);
        assertThat(executedSql).hasSize(1);
        assertThat(snapshot().contractHash()).isEqualTo(runtime().getContractHash()).isNotEqualTo(old.getContractHash());
        verifyNoInteractions(redis);
    }

    @Test
    void naturalExpiryDuringQueryDiscardsTheResultWithoutARevisionWrite() throws Exception {
        long boundary = databaseNow() + TimeUnit.SECONDS.toMillis(5);
        replaceValidity("A", 0, boundary);
        long revision = snapshot().aggregateRevision();
        duringQuery = () -> {
            assertThat(databaseNow()).as("fixture must enter query before expiry").isLessThan(boundary);
            awaitDatabaseBoundary(boundary);
        };
        assertCode(() -> execute(runtime()), SCOPE_CHANGED);
        assertThat(executedSql).hasSize(1);
        EffectiveGrantSnapshot after = snapshot();
        assertThat(after.aggregateRevision()).isEqualTo(revision);
        assertThat(after.approvedColumns()).containsExactly("id", "phone");
        duringQuery = () -> { };
        execute(runtime());
        assertThat(executedSql).hasSize(2);
        assertThat(executedSql.getLast()).contains("\"phone\"").doesNotContain("name");
        verifyNoInteractions(redis);
    }

    private DataApiDO runtime() {
        DataApiDO api = apis.selectById(API_ID);
        assertThat(api).isNotNull();
        return api;
    }

    private EffectiveGrantSnapshot snapshot() {
        return reader.snapshot( APP_ID, API_ID);
    }

    private DataApiExecuteRespVO execute(DataApiDO api) {
        return executor.execute(api, Map.of(), null, null, "127.0.0.1", false, false, APP_ID);
    }

    private void assertRejectedWithoutQuery(DataApiDO api, int code) {
        assertCode(() -> execute(api), code);
        assertThat(executedSql).isEmpty();
        verifyNoInteractions(redis);
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, int code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ServiceException.class,
                failure -> assertThat(failure.getCode()).isEqualTo(code));
    }

    /** 新合同只能由新来源批准；旧来源保留墓碑，不原地替换其不可兼容的冻结范围。 */
    private void publishAndApproveNewContract() throws Exception {
        DataApiDO next = runtime();
        try (var writer = open()) {
            writer.setAutoCommit(false);
            try {
                // 哈希基于 PostgreSQL JSONB 读回的表示，避免把 JSON 空白差异误当成被测缺口。
                try (var statement = writer.prepareStatement("SELECT CAST(? AS jsonb)::text")) {
                    statement.setString(1, "[{\"name\":\"id\"},{\"name\":\"name\",\"model\":\"HIDE\"},{\"name\":\"phone\"}]");
                    try (var result = statement.executeQuery()) {
                        assertThat(result.next()).isTrue();
                        next.setResponseFieldsJson(result.getString(1));
                    }
                }
                next.setContractHash(DataApiContract.contractHash(next));
                try (var statement = writer.prepareStatement("UPDATE dh_supply_api SET response_fields=CAST(? AS jsonb),"
                        + "contract_hash=?,row_version=row_version+1,active_release_id=101 WHERE id=10")) {
                    statement.setString(1, next.getResponseFieldsJson());
                    statement.setString(2, next.getContractHash());
                    assertThat(statement.executeUpdate()).isEqualTo(1);
                }
                try (var statement = writer.createStatement()) {
                    statement.executeUpdate("UPDATE dh_supply_grant_source SET source_status='REVOKED',"
                            + "source_revision=source_revision+1 WHERE source_key IN ('A','B')");
                    statement.executeUpdate("UPDATE dh_supply_app_api SET aggregate_revision=aggregate_revision+1");
                }
                source(writer, "new-contract-approval", next, List.of("id", "name", "phone"), 0, null);
                writer.commit();
            } catch (Exception failure) {
                writer.rollback();
                throw failure;
            }
        }
    }

    private void source(Connection writer, String key, DataApiDO api, List<String> columns,
                        long from, Long to) throws SQLException {
        Scope scope = scope(key, api, columns, from, to);
        try (var statement = writer.prepareStatement("INSERT INTO dh_supply_grant_source"
                + "(source_system,source_key,app_id,api_id,source_revision,source_status,scope_json,"
                + "payload_hash,compatibility_key) VALUES('asset',?,1,10,1,'ACTIVE',CAST(? AS jsonb),'fixture',?)")) {
            statement.setString(1, key);
            statement.setString(2, JsonUtils.toJsonString(scope));
            statement.setString(3, SupplyGrantCommandCodec.compatibilityHash(scope));
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    /** 仅设置测试的时间前提；真实运行校验仍使用数据库 clock_timestamp()。 */
    private void replaceValidity(String key, long from, Long to) throws Exception {
        Scope scope = scope(key, runtime(), "A".equals(key) ? List.of("id", "name") : List.of("id", "phone"), from, to);
        try (var writer = open(); var statement = writer.prepareStatement("UPDATE dh_supply_grant_source"
                + " SET scope_json=CAST(? AS jsonb),compatibility_key=? WHERE source_key=?")) {
            statement.setString(1, JsonUtils.toJsonString(scope));
            statement.setString(2, SupplyGrantCommandCodec.compatibilityHash(scope));
            statement.setString(3, key);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private static Scope scope(String key, DataApiDO api, List<String> columns, long from, Long to) {
        return new Scope("approval:" + key, "PERSONAL", USER_ID, new Binding(9L, "public", "people"),
                api.getContractHash(), api.getVersion(), "purpose", "duty", "CURRENT_USER_GOVERNANCE",
                columns, to == null, from, to);
    }

    private void changeIndependently(String change) throws Exception {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                .as("external query must not hold a grant transaction").isFalse();
        try (var writer = open()) {
            writer.setAutoCommit(false);
            try (var statement = writer.createStatement()) {
                statement.execute("SET LOCAL statement_timeout='2s'");
                statement.execute(change);
                writer.commit();
            } catch (Exception failure) {
                writer.rollback();
                throw failure;
            }
        }
    }

    private long databaseNow() throws SQLException {
        return scalar("SELECT floor(extract(epoch FROM clock_timestamp())*1000)::bigint");
    }

    /** 等待可观测的数据库时间边界，有上限；不修改时钟、不凭固定 sleep 猜测到期。 */
    private void awaitDatabaseBoundary(long boundary) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (databaseNow() < boundary) {
            if (System.nanoTime() >= deadline) throw new AssertionError("Database did not reach expiry boundary");
            Thread.sleep(20);
        }
    }

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws Exception;
    }

    /**
     * 取数执行生产投影后的 SQL，直接检查 ResultSet 的实际列；不声称覆盖远端治理行权限或生产 JDBC 适配器。
     * SQL 投影、字段模型、真实授权初读/末读和缓存决策全部使用生产代码。
     */
    private class RecordingExecutor extends DataApiExecutor {
        @Override
        DataApiExecuteRespVO queryDatabase(DataApiDO api, String sql, List<Object> binds,
                Integer pageNo, Integer pageSize, ExecuteContext permissionCtx) throws Exception {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(permissionCtx).isNotNull();
            assertThat(permissionCtx.userId()).isEqualTo(USER_ID);
            executedSql.add(sql);
            try (var dataConnection = open(); var statement = dataConnection.prepareStatement(sql)) {
                statement.setQueryTimeout(10);
                for (int i = 0; i < binds.size(); i++) statement.setObject(i + 1, binds.get(i));
                try (var rows = statement.executeQuery()) {
                    // 取数尚未返回执行器；另一个连接提交变化，最终授权复验必须看到它。
                    duringQuery.run();
                    var metadata = rows.getMetaData();
                    List<Map<String, Object>> values = new ArrayList<>();
                    while (rows.next()) {
                        var row = new LinkedHashMap<String, Object>();
                        for (int i = 1; i <= metadata.getColumnCount(); i++) {
                            row.put(metadata.getColumnLabel(i), rows.getObject(i));
                        }
                        values.add(row);
                    }
                    var result = new DataApiExecuteRespVO();
                    result.setRows(values);
                    return result;
                }
            }
        }
    }
}
