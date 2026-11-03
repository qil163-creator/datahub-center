package cn.iocoder.yudao.module.modeling.service.model;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.*;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.*;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.incrementer.PostgreKeyGenerator;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 当前基线与真实生产 Mapper；每次运行使用独立临时数据库。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModelingSingleTenantPostgreSqlTest {
    private String controlUrl;
    private String user;
    private String database;
    private JdbcTemplate jdbc;
    private AnnotationConfigApplicationContext context;
    private LogicModelMapper models;
    private LogicModelColumnMapper columns;
    private ModelMaterializedMapper materialized;
    private ModelMaterializedLogMapper logs;
    private ModelDeploymentAttemptStore store;
    private Path root;

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Transactions { }

    @BeforeAll
    void initialize() throws Exception {
        controlUrl = System.getenv("SHARING_TEST_JDBC_URL");
        assumeTrue(controlUrl != null && !controlUrl.isBlank(), "Explicit isolated PostgreSQL is required");
        user = System.getenv().getOrDefault("SHARING_TEST_JDBC_USER", "sharing_test");
        if (!controlUrl.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/postgres")
                || !Set.of("sharing_test", "dataforge_test", "codex_test").contains(user)) {
            throw new IllegalStateException("Only a local isolated test server and test role are accepted");
        }
        database = "modeling_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(controlUrl, user, "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        String url = controlUrl.substring(0, controlUrl.lastIndexOf('/') + 1) + database;
        var source = new DriverManagerDataSource(url + "?currentSchema=datahub-center", user, "");
        jdbc = new JdbcTemplate(source);
        root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("sql/postgresql/datahub-center.sql"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Missing repository baseline");
        jdbc.execute(Files.readString(root.resolve("sql/postgresql/datahub-center.sql")));

        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setLocalCacheScope(LocalCacheScope.STATEMENT);
        config.setEnvironment(new Environment("modeling-single-tenant", new SpringManagedTransactionFactory(), source));
        var global = GlobalConfigUtils.getGlobalConfig(config);
        global.setMetaObjectHandler(new DefaultDBFieldHandler());
        global.getDbConfig().setIdType(IdType.INPUT).setLogicDeleteValue("1").setLogicNotDeleteValue("0")
                .setKeyGenerators(List.of(new PostgreKeyGenerator()));
        for (var type : List.of(LogicModelMapper.class, LogicModelColumnMapper.class,
                ModelMaterializedMapper.class, ModelMaterializedLogMapper.class, ModelDeploymentAttemptMapper.class)) {
            config.addMapper(type);
        }
        var sessions = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        models = sessions.getMapper(LogicModelMapper.class);
        columns = sessions.getMapper(LogicModelColumnMapper.class);
        materialized = sessions.getMapper(ModelMaterializedMapper.class);
        logs = sessions.getMapper(ModelMaterializedLogMapper.class);
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.registerBean(LogicModelMapper.class, () -> models);
        context.registerBean(LogicModelColumnMapper.class, () -> columns);
        context.registerBean(LogicModelColumnService.class, LogicModelColumnServiceImpl::new);
        context.registerBean(LogicModelService.class, () -> org.mockito.Mockito.mock(LogicModelService.class));
        context.registerBean(ModelMaterializedMapper.class, () -> materialized);
        context.registerBean(ModelMaterializedLogMapper.class, () -> logs);
        context.registerBean(ModelDeploymentAttemptMapper.class, () -> sessions.getMapper(ModelDeploymentAttemptMapper.class));
        context.registerBean(ModelDeploymentAttemptStore.class);
        context.refresh();
        store = context.getBean(ModelDeploymentAttemptStore.class);
        assertThat(AopUtils.isAopProxy(store)).isTrue();
    }

    @BeforeEach
    void identity() {
        TenantContextHolder.clear();
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(new LoginUser().setId(42L), null));
    }

    @AfterEach
    void clearIdentity() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @AfterAll
    void cleanup() throws Exception {
        if (context != null) context.close();
        if (database != null && database.matches("modeling_test_[0-9a-f]{32}")) {
            try (var connection = DriverManager.getConnection(controlUrl, user, "");
                 var statement = connection.createStatement()) {
                statement.execute("DROP DATABASE " + database);
            }
        }
    }

    @Test
    void authoringFieldsKeepModelScopeStableIdentityAndLogicalDeleteWithoutTenantColumns() throws Exception {
        var first = model("authoring-first");
        var second = model("authoring-second");
        var column = column(first.getId(), "amount");
        columns.insert(column);
        columns.insert(column(second.getId(), "amount"));
        assertThat(columns.selectListByModelId(first.getId())).extracting(LogicModelColumnDO::getId).containsExactly(column.getId());
        assertThat(columns.selectById(column.getId()).getDataPrecision()).isEqualTo(38);
        assertThatThrownBy(() -> columns.insert(column(first.getId(), "amount")))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        columns.updateById(LogicModelColumnDO.builder().id(column.getId()).comment("updated").build());
        assertThat(columns.selectById(column.getId()).getFieldUid()).isEqualTo("amount");
        columns.deleteByModelId(first.getId());
        assertThat(columns.selectListByModelId(first.getId())).isEmpty();
        assertThat(columns.selectListByModelId(second.getId())).hasSize(1);
        columns.insert(column(first.getId(), "amount"));
        jdbc.execute(Files.readString(root.resolve("sql/postgresql/migrations/0169_datahub_remove-business-tenancy.sql")));
        assertThat(columns.selectListByModelId(first.getId())).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema='datahub-center' "
                + "AND table_name LIKE 'dh_modeling_%' AND column_name='tenant_id'", Long.class)).isZero();
    }

    @Test
    void deploymentReservesAndFinishesWithCurrentDesignInputAndActorAudit() {
        var model = model("deployment-success");
        var attempt = store.reserve(desired(model, 10L), attempt(model, 10L, "target-success"));
        assertThat(attempt.getId()).isPositive();
        assertThat(store.get(attempt.getId()).getFieldsJson()).isEqualTo("[]");
        store.executing(attempt);
        store.heartbeat(attempt);
        assertThat(store.get(attempt.getId()).getPhase()).isEqualTo("EXECUTING");
        store.finish(attempt, "SUCCESS", "Fixture completed");
        assertThat(store.get(attempt.getId()).getPhase()).isEqualTo("FINISHED");
        assertThat(materialized.selectById(attempt.getMaterializedId()).getStatus()).isEqualTo("SUCCESS");
        assertThat(logs.selectList()).anySatisfy(log -> {
            assertThat(log.getMaterializedId()).isEqualTo(attempt.getMaterializedId());
            assertThat(log.getOperator()).isEqualTo("42");
            assertThat(log.getStatus()).isEqualTo("SUCCESS");
        });
    }

    @Test
    void globalTargetConflictRollsBackNewMaterializedRowAndDesignInputMismatchIsRejected() {
        var first = model("deployment-first");
        store.reserve(desired(first, 20L), attempt(first, 20L, "global-target"));
        var second = model("deployment-second");
        assertThatThrownBy(() -> store.reserve(desired(second, 21L), attempt(second, 21L, "global-target")))
                .isInstanceOf(ServiceException.class);
        assertThat(materialized.selectByModelAndDatasource(second.getId(), 21L)).isNull();
        var changed = attempt(second, 21L, "new-target");
        changed.setFieldsJson("[{\"changed\":true}]");
        assertThatThrownBy(() -> store.reserve(desired(second, 21L), changed)).isInstanceOf(ServiceException.class);
        assertThat(materialized.selectByModelAndDatasource(second.getId(), 21L)).isNull();
    }

    @Test
    void recoveryFencesStaleOwnerAndUnknownOutcomeKeepsTheTargetOccupied() {
        var model = model("deployment-recovery");
        var stale = store.reserve(desired(model, 30L), attempt(model, 30L, "recovery-target"));
        store.executing(stale);
        jdbc.update("UPDATE dh_modeling_deployment_attempt SET lease_until=CURRENT_TIMESTAMP-INTERVAL '1 minute' WHERE id=?", stale.getId());
        var owner = store.claimRecovery(stale.getId());
        assertThat(owner.getLeaseOwner()).isNotEqualTo(stale.getLeaseOwner());
        assertThat(owner.getStatus()).isEqualTo("UNKNOWN");
        assertThatThrownBy(() -> store.finish(stale, "SUCCESS", "stale outcome")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> store.reserve(desired(model, 30L), attempt(model, 30L, "recovery-target")))
                .isInstanceOf(ServiceException.class);
        store.finish(owner, "FAIL", "Reconciled failure");
        var retry = store.reserve(desired(model, 30L), attempt(model, 30L, "recovery-target"));
        assertThat(retry.getId()).isNotEqualTo(stale.getId());
    }

    private LogicModelDO model(String code) {
        var model = LogicModelDO.builder().name(code).code(code).enabled(1).status("PUBLISHED").build();
        models.insert(model);
        return models.selectById(model.getId());
    }

    private LogicModelColumnDO column(Long modelId, String uid) {
        return LogicModelColumnDO.builder().modelId(modelId).fieldUid(uid).fieldName(uid).dataType("numeric")
                .dataPrecision(38).dataScale(18).pkFlag(0).nullableFlag(1).sort(1).build();
    }

    private ModelMaterializedDO desired(LogicModelDO model, long datasource) {
        return ModelMaterializedDO.builder().modelId(model.getId()).datasourceId(datasource)
                .publishMode("REBUILD").modelName(model.getCode()).schemaName("public").tableName("customer").build();
    }

    private ModelDeploymentAttemptDO attempt(LogicModelDO model, long datasource, String target) {
        var attempt = new ModelDeploymentAttemptDO();
        attempt.setModelId(model.getId()); attempt.setDesignRevision(model.getRevision()); attempt.setDatasourceId(datasource); attempt.setTargetKey(target);
        attempt.setSchemaName("public"); attempt.setTableName("customer"); attempt.setConfigurationRevision("r1");
        attempt.setPublishMode("REBUILD"); attempt.setBeforeObservationJson("{}");
        attempt.setDdlSql("CREATE TABLE customer(id bigint)");
        attempt.setFieldsJson("[]"); attempt.setModelSnapshotJson(ModelDeploymentAttemptStore.taskModelInput(model));
        return attempt;
    }
}
