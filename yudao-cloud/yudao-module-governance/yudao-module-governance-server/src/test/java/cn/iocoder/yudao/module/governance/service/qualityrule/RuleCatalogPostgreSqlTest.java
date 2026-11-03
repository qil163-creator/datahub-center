package cn.iocoder.yudao.module.governance.service.qualityrule;

import cn.iocoder.yudao.module.governance.controller.admin.qualityrule.vo.AuditRulePageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.qualityrule.vo.CleanRulePageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.qualityrule.AuditRuleDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.qualityrule.CleanRuleDO;
import cn.iocoder.yudao.module.governance.dal.mysql.categorycleanrule.CategoryCleanRuleMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.qualityrule.AuditRuleDimensionMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.qualityrule.AuditRuleMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.qualityrule.CleanRuleMapper;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 在显式隔离 PostgreSQL 中验证真实规则 Mapper 分页、稳定排序和父类目递归。 */
@Timeout(30)
class RuleCatalogPostgreSqlTest {
    private final String schema = "rule_catalog_" + UUID.randomUUID().toString().replace("-", "");
    private String url;
    private String user;
    private String password;
    private boolean created;
    private AuditRuleServiceImpl audits;
    private CleanRuleServiceImpl cleans;

    @BeforeEach
    void setup() throws Exception {
        url = System.getProperty("datahub.test.postgres.url");
        assumeTrue(url != null && !url.isBlank(), "需要显式隔离 PostgreSQL 地址");
        assertTrue(url.matches("jdbc:postgresql://(?:127[.]0[.]0[.]1|localhost):[0-9]{1,5}/[A-Za-z0-9_]+"));
        user = System.getProperty("datahub.test.postgres.user", "postgres");
        password = System.getProperty("datahub.test.postgres.password", "");
        try (var connection = DriverManager.getConnection(url, user, password)) {
            execute(connection, "CREATE SCHEMA " + schema);
            created = true;
            execute(connection, "SET search_path TO " + schema);
            for (String kind : List.of("audit", "clean")) {
                String categoryColumn = kind.equals("audit") ? "dimension_id" : "category_id";
                execute(connection, "CREATE TABLE dh_governance_" + kind + "_rule (id bigint PRIMARY KEY, name varchar(100), "
                        + categoryColumn + " bigint, rule_level smallint, code varchar(32), strategy_key varchar(64), "
                        + "description varchar(500), use_case varchar(500), example varchar(2000), status smallint, "
                        + "creator varchar(64), create_time timestamp, updater varchar(64), update_time timestamp, "
                        + "deleted smallint NOT NULL DEFAULT 0" + (kind.equals("clean") ? ", config_json jsonb" : "") + ")");
                execute(connection, "INSERT INTO dh_governance_" + kind + "_rule (id,name," + categoryColumn
                        + ",description,status,create_time,deleted) VALUES "
                        + "(1,'字段甲',1,'通用说明',0,'2026-10-01',0),"
                        + "(2,'字段乙',2,'通用说明',0,'2026-10-02',0),"
                        + "(3,'字段丙',3,'通用说明',0,'2026-10-02',0),"
                        + "(4,'其他',9,'其他说明',1,'2026-10-03',0),"
                        + "(5,'已删除',1,'通用说明',0,'2026-10-04',1)");
            }
            execute(connection, "CREATE TABLE dh_governance_audit_rule_dimension (id bigint PRIMARY KEY, "
                    + "name varchar(64), code varchar(64), sort integer, status smallint, remark varchar(500), "
                    + "creator varchar(64), create_time timestamp, updater varchar(64), update_time timestamp, "
                    + "deleted smallint NOT NULL DEFAULT 0)");
            execute(connection, "INSERT INTO dh_governance_audit_rule_dimension (id,name,code,sort,status) VALUES "
                    + "(1,'一致性','D01',3,0),(2,'完整性','D02',4,0),(3,'规范性','D03',1,0),(9,'准确性','D05',0,0)");
            for (String category : List.of("data_quality", "clean_rule")) {
                execute(connection, "CREATE TABLE dh_governance_category_" + category + " (id bigint PRIMARY KEY, "
                        + "parent_id bigint, category_name varchar(100), category_code varchar(100), sort integer, status smallint, "
                        + "remark varchar(500), creator varchar(64), create_time timestamp, updater varchar(64), update_time timestamp, "
                        + "deleted smallint NOT NULL DEFAULT 0)");
                execute(connection, "INSERT INTO dh_governance_category_" + category
                        + " (id,parent_id,category_name,category_code,sort,status) VALUES "
                        + "(1,0,'父类','0,1',1,0),(2,1,'子类','0,1,2',1,0),"
                        + "(3,2,'孙类','0,1,2,3',1,0),(9,0,'其他','0,9',2,0)");
            }
        }
        var source = new UnpooledDataSource("org.postgresql.Driver", url + "?currentSchema=" + schema
                + "&connectTimeout=5&socketTimeout=10", user, password);
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setDefaultStatementTimeout(10);
        config.setEnvironment(new Environment("rule-catalog", new JdbcTransactionFactory(), source));
        var pagination = new MybatisPlusInterceptor();
        pagination.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        config.addInterceptor(pagination);
        config.addMapper(AuditRuleMapper.class);
        config.addMapper(CleanRuleMapper.class);
        config.addMapper(AuditRuleDimensionMapper.class);
        config.addMapper(CategoryCleanRuleMapper.class);
        var sessions = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        audits = new AuditRuleServiceImpl();
        cleans = new CleanRuleServiceImpl();
        ReflectionTestUtils.setField(audits, "auditRuleMapper", sessions.getMapper(AuditRuleMapper.class));
        var dimensions = new AuditRuleDimensionServiceImpl();
        ReflectionTestUtils.setField(dimensions, "auditRuleDimensionMapper", sessions.getMapper(AuditRuleDimensionMapper.class));
        ReflectionTestUtils.setField(audits, "auditRuleDimensionService", dimensions);
        ReflectionTestUtils.setField(cleans, "cleanRuleMapper", sessions.getMapper(CleanRuleMapper.class));
        ReflectionTestUtils.setField(cleans, "categoryCleanRuleMapper", sessions.getMapper(CategoryCleanRuleMapper.class));
    }

    @AfterEach
    void cleanup() throws Exception {
        if (!created) return;
        assertTrue(schema.matches("rule_catalog_[a-f0-9]{32}"));
        try (var connection = DriverManager.getConnection(url, user, password)) {
            execute(connection, "DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test
    void defaultSortingHasStableTieBreakAcrossActualPagesAndExcludesDeletedRows() {
        var audit = new AuditRulePageReqVO(); audit.setPageSize(2);
        var clean = new CleanRulePageReqVO(); clean.setPageSize(2);
        assertRows(audit, clean, List.of(4L, 3L), 4);
        audit.setPageNo(2); clean.setPageNo(2);
        assertRows(audit, clean, List.of(2L, 1L), 4);
    }

    @Test
    void bothWhitelistedSortColumnsAndDirectionsExecuteOnRealMappers() {
        var audit = new AuditRulePageReqVO();
        var clean = new CleanRulePageReqVO();
        for (String field : List.of("id", "createTime")) {
            audit.setSortField(field); clean.setSortField(field);
            audit.setSortOrder("asc"); clean.setSortOrder("asc");
            assertRows(audit, clean, List.of(1L, 2L, 3L, 4L), 4);
            audit.setSortOrder("desc"); clean.setSortOrder("desc");
            assertRows(audit, clean, List.of(4L, 3L, 2L, 1L), 4);
        }
    }

    @Test
    void auditDimensionsAreFlatWhileCleanCategoriesRetainDescendantFiltering() {
        var audit = new AuditRulePageReqVO(); audit.setDimensionId(1L);
        var clean = new CleanRulePageReqVO(); clean.setCategoryId(1L);
        assertEquals(List.of(1L), audits.getAuditRulePage(audit).getList().stream().map(AuditRuleDO::getId).toList());
        assertEquals(1L, audits.getAuditRulePage(audit).getTotal());
        assertEquals(List.of(3L, 2L, 1L), cleans.getCleanRulePage(clean).getList().stream().map(CleanRuleDO::getId).toList());
        audit.setDimensionId(2L); clean.setCategoryId(2L);
        assertEquals(List.of(2L), audits.getAuditRulePage(audit).getList().stream().map(AuditRuleDO::getId).toList());
        assertEquals(List.of(3L, 2L), cleans.getCleanRulePage(clean).getList().stream().map(CleanRuleDO::getId).toList());
        audit.setName("字段乙"); clean.setName("字段乙");
        audit.setDescription("通用"); clean.setDescription("通用");
        audit.setStatus(0); clean.setStatus(0);
        assertRows(audit, clean, List.of(2L), 1);
        audit.setStatus(1); clean.setStatus(1);
        assertRows(audit, clean, List.of(), 0);
        audit.setDimensionId(999L); clean.setCategoryId(999L);
        assertRows(audit, clean, List.of(), 0);
    }

    private void assertRows(AuditRulePageReqVO audit, CleanRulePageReqVO clean, List<Long> expected, long total) {
        var auditPage = audits.getAuditRulePage(audit);
        var cleanPage = cleans.getCleanRulePage(clean);
        assertEquals(total, auditPage.getTotal());
        assertEquals(total, cleanPage.getTotal());
        assertEquals(expected, auditPage.getList().stream().map(AuditRuleDO::getId).toList());
        assertEquals(expected, cleanPage.getList().stream().map(CleanRuleDO::getId).toList());
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(10);
            statement.execute(sql);
        }
    }
}
