package cn.iocoder.yudao.module.modeling.service.model;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.logic.LogicModelPageReqVO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.LogicModelDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.LogicModelMapper;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** 生产 Mapper 在真实 PostgreSQL 中按标准文件筛选，并执行真实分页和逻辑删除条件。 */
@Testcontainers(disabledWithoutDocker = true)
class LogicModelDocumentPagePostgreSqlTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final long DOCUMENT_ID = 9007199254741001L;
    private static final long OTHER_DOCUMENT_ID = 9007199254741002L;
    private static SqlSessionFactory sessions;

    @BeforeAll
    static void initializeMapperAndFixture() throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            // 夹具包含实体完整投影列，查询由生产 Mapper 和分页插件生成。
            statement.execute("""
                    CREATE SCHEMA model_document_test;
                    SET search_path TO model_document_test;
                    CREATE TABLE dh_modeling_logic_model (
                        id bigint PRIMARY KEY, revision bigint, layout_revision bigint, grain text,
                        name varchar(100), code varchar(100), create_mode varchar(32), latest_version integer,
                        status varchar(32), description text, model_style varchar(32), warehouse_layer varchar(32),
                        layer_id bigint, subject_domain_id bigint, table_type varchar(32), classification_status varchar(32),
                        data_domain_id bigint, biz_category_id bigint, table_case integer, document_type varchar(32),
                        document_id bigint, contact_user_id bigint, contact_number varchar(64), enabled integer,
                        remark text, pos_x integer, pos_y integer, creator varchar(64), updater varchar(64),
                        create_time timestamp, update_time timestamp, deleted smallint NOT NULL DEFAULT 0
                    );
                    INSERT INTO dh_modeling_logic_model (id, name, code, status, document_id, deleted) VALUES
                        (101, '当前标准甲', 'shared_first', 'REVISING', 9007199254741001, 0),
                        (102, '其他标准', 'shared_other', 'PUBLISHED', 9007199254741002, 0),
                        (103, '当前标准乙', 'shared_second', 'PUBLISHED', 9007199254741001, 0),
                        (104, '未绑定标准', 'shared_unbound', 'PUBLISHED', NULL, 0),
                        (105, '已删除模型', 'shared_deleted', 'PUBLISHED', 9007199254741001, 1);
                    """);
        }
        var properties = new Properties();
        properties.setProperty("user", POSTGRES.getUsername());
        properties.setProperty("password", POSTGRES.getPassword());
        properties.setProperty("currentSchema", "model_document_test");
        var source = new UnpooledDataSource("org.postgresql.Driver", POSTGRES.getJdbcUrl(), properties);
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setDefaultStatementTimeout(10);
        configuration.setEnvironment(new Environment("logic-model-document-page", new JdbcTransactionFactory(), source));
        GlobalConfigUtils.getGlobalConfig(configuration).getDbConfig()
                .setLogicDeleteValue("1").setLogicNotDeleteValue("0");
        var pagination = new MybatisPlusInterceptor();
        pagination.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        configuration.addInterceptor(pagination);
        configuration.addMapper(LogicModelMapper.class);
        sessions = new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    @Test
    void documentFilterExcludesOtherUnboundAndDeletedModelsWithStablePageTotals() {
        try (var session = sessions.openSession(true)) {
            var mapper = session.getMapper(LogicModelMapper.class);
            var request = request(DOCUMENT_ID, 1, 1);
            PageResult<LogicModelDO> first = mapper.selectPage(request);
            assertEquals(2L, first.getTotal());
            assertEquals(List.of(103L), ids(first));
            assertEquals(DOCUMENT_ID, first.getList().getFirst().getDocumentId());

            request.setPageNo(2);
            PageResult<LogicModelDO> second = mapper.selectPage(request);
            assertEquals(2L, second.getTotal());
            assertEquals(List.of(101L), ids(second));

            request.setPageNo(3);
            PageResult<LogicModelDO> beyondLast = mapper.selectPage(request);
            assertEquals(2L, beyondLast.getTotal());
            assertTrue(beyondLast.getList().isEmpty());

            PageResult<LogicModelDO> other = mapper.selectPage(request(OTHER_DOCUMENT_ID, 1, 10));
            assertEquals(1L, other.getTotal());
            assertEquals(List.of(102L), ids(other));

            PageResult<LogicModelDO> missing = mapper.selectPage(request(9007199254741099L, 1, 10));
            assertEquals(0L, missing.getTotal());
            assertTrue(missing.getList().isEmpty());
        }
    }

    @Test
    void documentFilterRemainsConjunctiveWithKeywordAndStatus() {
        try (var session = sessions.openSession(true)) {
            var request = request(DOCUMENT_ID, 1, 10);
            request.setKeyword("shared");
            request.setStatus(LogicModelDO.STATUS_PUBLISHED);

            PageResult<LogicModelDO> page = session.getMapper(LogicModelMapper.class).selectPage(request);

            assertEquals(1L, page.getTotal());
            assertEquals(List.of(103L), ids(page));
        }
    }

    @Test
    void omittedDocumentIdRetainsExistingGlobalListingWithoutDeletedRows() {
        try (var session = sessions.openSession(true)) {
            PageResult<LogicModelDO> page = session.getMapper(LogicModelMapper.class)
                    .selectPage(request(null, 1, 10));

            assertEquals(4L, page.getTotal());
            assertEquals(List.of(104L, 103L, 102L, 101L), ids(page));
        }
    }

    private static LogicModelPageReqVO request(Long documentId, int pageNo, int pageSize) {
        var request = new LogicModelPageReqVO();
        request.setDocumentId(documentId);
        request.setPageNo(pageNo);
        request.setPageSize(pageSize);
        return request;
    }

    private static List<Long> ids(PageResult<LogicModelDO> page) {
        return page.getList().stream().map(LogicModelDO::getId).toList();
    }
}
