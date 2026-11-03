package cn.iocoder.yudao.module.modeling.service.model;

import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.materialized.ModelMaterializedPageReqVO;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.materialized.ModelMaterializedRespVO;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.LogicModelMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.ModelDeploymentAttemptMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.ModelMaterializedMapper;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

/** 生产查询在独立 PostgreSQL schema 验证类型/来源筛选、最新发布排序和真实分页。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModelMaterializedPagePostgreSqlTest {
    private Connection connection;
    private SqlSessionFactory sessions;
    private final String schema = "materialized_page_" + UUID.randomUUID().toString().replace("-", "");

    @BeforeAll void initialize() throws Exception {
        String url = System.getProperty("datahub.test.postgres.url");
        assumeTrue(url != null && url.matches("^jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost|\\[::1\\]):[0-9]+/[A-Za-z0-9_]+$"),
                "需指定本次隔离的本机 PostgreSQL 实例");
        String user = System.getProperty("datahub.test.postgres.user", "dataforge_test");
        connection = DriverManager.getConnection(url, user, "");
        execute("CREATE SCHEMA " + schema);
        execute("SET search_path TO " + schema);
        execute("""
                CREATE TABLE dh_modeling_logic_model (
                    id bigint PRIMARY KEY, revision bigint, layout_revision bigint, grain text,
                    name text, code text, create_mode text, latest_version integer, status text, description text,
                    model_style text, warehouse_layer text, layer_id bigint, subject_domain_id bigint, table_type text,
                    classification_status text, data_domain_id bigint, biz_category_id bigint, table_name_notation text,
                    source_system_id bigint, datasource_id bigint, db_type text, db_name text, schema_name text,
                    entity_id bigint, obj_type text, business_type text, data_timeliness text, statistics_str text,
                    owner_name text, owner_phone text, table_case integer, document_type text, document_id bigint,
                    contact_user_id bigint, contact_number text, enabled integer, remark text, pos_x integer, pos_y integer,
                    creator text, updater text, create_time timestamp, update_time timestamp, deleted smallint DEFAULT 0
                );
                CREATE TABLE dh_modeling_model_materialized (
                    id bigint PRIMARY KEY, model_id bigint, datasource_id bigint, version_id bigint, attempt_id bigint,
                    datasource_type text, datasource_name text, model_name text, model_alias text, schema_name text,
                    table_name text, publish_mode text, status text, ddl_sql text, message text, description text, remark text,
                    creator text, updater text, create_time timestamp, update_time timestamp, deleted smallint DEFAULT 0
                );
                CREATE TABLE dh_modeling_deployment_attempt (id bigint PRIMARY KEY, finished_time timestamp, deleted smallint DEFAULT 0);
                INSERT INTO dh_modeling_logic_model (id, name, code, table_type, layer_id, source_system_id, enabled, deleted) VALUES
                    (101, '维度甲', 'dim_a', 'DIMENSION', 3, 8, 1, 0),
                    (102, '维度乙', 'dim_b', 'DIMENSION', 3, 8, 1, 0),
                    (103, '汇总模型', 'summary_a', 'SUMMARY', 4, 8, 1, 0),
                    (104, '贴源模型', 'ods_a', 'SOURCE', 5, 9, 1, 0),
                    (105, '禁用模型', 'disabled', 'DIMENSION', 3, 8, 0, 0),
                    (106, '已删除模型', 'deleted', 'DIMENSION', 3, 8, 1, 1),
                    (107, '未发布模型', 'unpublished', 'DIMENSION', 3, 8, 1, 0);
                INSERT INTO dh_modeling_deployment_attempt (id, finished_time) VALUES
                    (1, '2030-01-01'), (2, '2026-10-01'), (3, '2026-10-02');
                INSERT INTO dh_modeling_model_materialized (id, model_id, datasource_id, attempt_id, status, update_time) VALUES
                    (1, 101, 6, 1, 'FAIL', '2026-09-01'),
                    (2, 101, 7, 2, 'SUCCESS', '2026-10-01'),
                    (3, 102, 7, 3, 'SUCCESS', '2026-10-02');
                """);
        var properties = new Properties(); properties.setProperty("user", user); properties.setProperty("currentSchema", schema);
        var configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true);
        configuration.setDefaultStatementTimeout(10);
        configuration.setEnvironment(new Environment("materialized-page-test", new JdbcTransactionFactory(),
                new UnpooledDataSource("org.postgresql.Driver", url, properties)));
        GlobalConfigUtils.getGlobalConfig(configuration).getDbConfig().setLogicDeleteValue("1").setLogicNotDeleteValue("0");
        var pagination = new MybatisPlusInterceptor(); pagination.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        configuration.addInterceptor(pagination); configuration.addMapper(LogicModelMapper.class); configuration.addMapper(ModelMaterializedMapper.class);
        sessions = new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    @AfterAll void cleanup() throws Exception {
        if (connection == null) return;
        try { execute("DROP SCHEMA " + schema + " CASCADE"); } finally { connection.close(); }
    }

    @Test void filtersSourceAndLayerAndShowsSourceModelsWithoutDisabledOrDeletedRows() {
        var dimensions = request(); dimensions.setTableType("DIMENSION"); dimensions.setLayerIds(List.of(3L, 4L)); dimensions.setSourceSystemId(8L);
        assertEquals(List.of(107L, 102L, 101L), ids(dimensions));
        var source = request(); source.setTableType("SOURCE"); source.setSourceSystemId(9L);
        assertEquals(List.of(104L), ids(source));
    }

    @Test void publishTimeSortUsesLatestTargetAndRetainsUnpublishedRowsWithStablePagination() {
        var request = request(); request.setTableType("DIMENSION"); request.setSortField("publishTime"); request.setSortOrder("asc");
        assertEquals(List.of(101L, 102L, 107L), ids(request));
        request.setSortOrder("desc"); assertEquals(List.of(102L, 101L, 107L), ids(request));
        request.setPageNo(2); request.setPageSize(1);
        try (var session = sessions.openSession()) {
            var result = service(session.getMapper(LogicModelMapper.class), session.getMapper(ModelMaterializedMapper.class)).getPage(request);
            assertEquals(3L, result.getTotal());
            assertEquals(List.of(101L), result.getList().stream().map(ModelMaterializedRespVO::getModelId).toList());
        }
    }

    @Test void targetAndStatusFiltersApplyToLatestDeploymentInsteadOfHistoricalSuccessOrFailure() {
        var request = request(); request.setPublishStatus("FAIL"); request.setDatasourceId(6L);
        assertEquals(List.of(), ids(request));
        request.setPublishStatus("SUCCESS"); request.setDatasourceId(7L);
        assertEquals(List.of(102L, 101L), ids(request));
    }

    private List<Long> ids(ModelMaterializedPageReqVO request) {
        try (var session = sessions.openSession()) {
            return service(session.getMapper(LogicModelMapper.class), session.getMapper(ModelMaterializedMapper.class))
                    .getPage(request).getList().stream().map(ModelMaterializedRespVO::getModelId).toList();
        }
    }

    private ModelMaterializedServiceImpl service(LogicModelMapper models, ModelMaterializedMapper targets) {
        var result = new ModelMaterializedServiceImpl();
        ReflectionTestUtils.setField(result, "logicModelMapper", models); ReflectionTestUtils.setField(result, "materializedMapper", targets);
        ReflectionTestUtils.setField(result, "hierarchyResolver", mock(ModelHierarchyResolver.class));
        ReflectionTestUtils.setField(result, "deploymentAttemptMapper", mock(ModelDeploymentAttemptMapper.class));
        return result;
    }

    private ModelMaterializedPageReqVO request() {
        var result = new ModelMaterializedPageReqVO(); result.setPageNo(1); result.setPageSize(10); return result;
    }

    private void execute(String sql) throws Exception {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }
}
