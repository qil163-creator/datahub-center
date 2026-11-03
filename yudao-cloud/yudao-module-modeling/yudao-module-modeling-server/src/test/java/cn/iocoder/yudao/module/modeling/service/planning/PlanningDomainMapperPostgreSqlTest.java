package cn.iocoder.yudao.module.modeling.service.planning;

import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.datadomain.DataDomainPageReqVO;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.subjectdomain.SubjectDomainListReqVO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.DataDomainDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.SubjectDomainDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.planning.*;
import cn.iocoder.yudao.module.modeling.dal.mysql.businessentity.*;
import cn.iocoder.yudao.module.modeling.dal.dataobject.businessentity.*;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.incrementer.PostgreKeyGenerator;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 克隆已升级临时库的真实表结构，验证生产映射、类型处理器、聚合与锁。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlanningDomainMapperPostgreSqlTest {
    private Connection connection;
    private SqlSessionFactory sessions;
    private final String schema = "planning_test_" + UUID.randomUUID().toString().replace("-", "");

    @BeforeAll
    void initialize() throws Exception {
        String url = System.getenv("MODEL_OVERVIEW_TEST_JDBC_URL");
        assumeTrue(url != null && !url.isBlank(), "需通过 verify_modeling_domain_workbench.py 的隔离 PostgreSQL 环境执行");
        String user = System.getenv().getOrDefault("MODEL_OVERVIEW_TEST_DB_USER", "domain_workbench_test");
        String password = System.getenv().getOrDefault("MODEL_OVERVIEW_TEST_DB_PASSWORD", "");
        connection = DriverManager.getConnection(url, user, password);
        execute("CREATE SCHEMA " + schema);
        execute("SET search_path TO " + schema);
        for (String table : List.of("dh_modeling_data_domain", "dh_modeling_subject_domain", "dh_modeling_concept_model",
                "dh_modeling_business_entity", "dh_modeling_business_entity_relation")) {
            execute("CREATE TABLE " + table + " (LIKE \"datahub-center\"." + table + " INCLUDING ALL)");
            execute("CREATE SEQUENCE " + table + "_seq START 900001");
        }
        var properties = new Properties();
        properties.setProperty("user", user); properties.setProperty("password", password); properties.setProperty("currentSchema", schema);
        // URL 中的同名参数优先级高于连接属性，必须移除，确保所有生产 Mapper 仅触及隔离 schema。
        int queryStart = url.indexOf('?');
        String mapperUrl = queryStart < 0 ? url : url.substring(0, queryStart);
        if (queryStart >= 0) {
            String query = java.util.Arrays.stream(url.substring(queryStart + 1).split("&"))
                    .filter(value -> !value.toLowerCase(java.util.Locale.ROOT).startsWith("currentschema="))
                    .collect(java.util.stream.Collectors.joining("&"));
            if (!query.isEmpty()) mapperUrl += "?" + query;
        }
        var datasource = new UnpooledDataSource("org.postgresql.Driver", mapperUrl, properties);
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("planning-test", new JdbcTransactionFactory(), datasource));
        var global = GlobalConfigUtils.getGlobalConfig(configuration);
        global.setMetaObjectHandler(new DefaultDBFieldHandler());
        global.getDbConfig().setIdType(IdType.INPUT).setLogicDeleteValue("1").setLogicNotDeleteValue("0")
                .setKeyGenerators(List.of(new PostgreKeyGenerator()));
        configuration.addMapper(DataDomainMapper.class);
        configuration.addMapper(SubjectDomainMapper.class);
        configuration.addMapper(PlanningDomainStatisticsMapper.class);
        configuration.addMapper(BusinessEntityMapper.class);
        configuration.addMapper(BusinessEntityRelationMapper.class);
        sessions = new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    @Test
    void mapsJsonRulesAndOwnerListsAndAggregatesRealPlanningEntities() throws Exception {
        Long boardId;
        Long subjectId;
        try (var session = sessions.openSession(false)) {
            var boards = session.getMapper(DataDomainMapper.class);
            var board = DataDomainDO.builder().name("测试板块").code("TEST_BOARD").status(1)
                    .leaderUserId(1L).businessOwnerIds(List.of(9007199254740993L)).dataOwnerIds(List.of())
                    .namingRules("[{\"tableType\":\"ODS\"}]").build();
            assertEquals(1, boards.insert(board));
            boardId = board.getId();
            var locked = boards.selectForUpdate(boardId);
            assertEquals(board.getBusinessOwnerIds(), locked.getBusinessOwnerIds());
            assertEquals("ODS", cn.iocoder.yudao.framework.common.util.json.JsonUtils.parseTree(locked.getNamingRules()).get(0).get("tableType").asString());
            assertEquals(boardId, boards.selectByCode("test_board").getId());
            board.setNamingRules("[]"); board.setBusinessOwnerIds(List.of());
            assertEquals(1, boards.updateById(board));
            assertEquals(List.of(), boards.selectById(boardId).getBusinessOwnerIds());
            assertEquals(1, boards.updateNamingRules(boardId, "[{\"tableType\":\"DIM\"}]", "test"));
            assertEquals("DIM", cn.iocoder.yudao.framework.common.util.json.JsonUtils.parseTree(
                    boards.selectById(boardId).getNamingRules()).get(0).get("tableType").asString());
            var subjects = session.getMapper(SubjectDomainMapper.class);
            var subject = SubjectDomainDO.builder().name("测试主题").code("TEST_SUBJECT").abbreviation("TS")
                    .dataDomainId(boardId).parentId(0L).leaderUserId(1L).status(1).build();
            assertEquals(1, subjects.insert(subject));
            subjectId = subject.getId();
            var filter = new SubjectDomainListReqVO(); filter.setDataDomainId(boardId); filter.setName("TS");
            assertEquals(1, subjects.selectList(filter).size());
            filter.setDataDomainId(boardId + 1);
            assertTrue(subjects.selectList(filter).isEmpty());
            var entityMapper = session.getMapper(BusinessEntityMapper.class);
            for (int index = 0; index < 3; index++) {
                var entity = new BusinessEntityDO();
                entity.setDataDomainId(boardId); entity.setSubjectDomainId(subjectId);
                entity.setName("实体" + index); entity.setCode("entity_" + index);
                entity.setEntityType(index == 2 ? "ACTIVITY" : "OBJECT");
                entity.setEntitySubtype(index == 2 ? "EVENT" : "NORMAL");
                entity.setStatus(1); entity.setRevision(0L);
                entity.setLeaderUserId(1L); entity.setLeaderPhone("00000000000"); entity.setDescription("待清空");
                assertEquals(1, entityMapper.insert(entity));
                assertEquals(entity.getId(), entityMapper.selectByCode(boardId, ("ENTITY_" + index)).getId());
                entity.setLeaderUserId(null); entity.setLeaderPhone(null); entity.setDescription(null);
                assertEquals(1, entityMapper.updateById(entity));
                var cleared = entityMapper.selectById(entity.getId());
                assertNull(cleared.getLeaderUserId()); assertNull(cleared.getLeaderPhone()); assertNull(cleared.getDescription());
            }
            var rows = entityMapper.selectByBoard(boardId);
            var edge = new BusinessEntityRelationDO();
            edge.setSourceEntityId(rows.get(0).getId()); edge.setTargetEntityId(rows.get(1).getId());
            edge.setRelationType("ASSOCIATION"); edge.setSortOrder(0);
            var edgeMapper = session.getMapper(BusinessEntityRelationMapper.class);
            assertEquals(1, edgeMapper.insert(edge));
            assertEquals(edge.getTargetEntityId(), edgeMapper.selectByBoard(boardId).getFirst().getTargetEntityId());
            assertEquals(1, edgeMapper.deleteById(edge.getId()));
            assertTrue(edgeMapper.selectByBoard(boardId).isEmpty());
            session.commit();
        }
        execute("INSERT INTO dh_modeling_concept_model(name,code,data_domain_id,subject_domain_id,design) VALUES ('统计模型','stats',"
                + boardId + "," + subjectId + ",'{\"entities\":[{\"entityType\":\"OBJECT\"},{\"entityType\":\"ACTIVITY\"},{}],\"relations\":[]}')");
        try (var session = sessions.openSession(true)) {
            var statistics = session.getMapper(PlanningDomainStatisticsMapper.class);
            assertEquals(1L, statistics.selectSubjectCounts().getFirst().getCount());
            var count = statistics.selectBoardEntityCounts().getFirst();
            assertEquals(2L, count.getCount()); assertEquals(1L, count.getActivityCount());
            assertEquals(3L, statistics.selectSubjectEntityCounts().getFirst().getCount());
        }
    }

    private void execute(String sql) throws Exception {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }

    @AfterAll
    void close() throws Exception {
        if (connection != null) {
            try { execute("DROP SCHEMA " + schema + " CASCADE"); }
            finally { connection.close(); }
        }
    }
}
