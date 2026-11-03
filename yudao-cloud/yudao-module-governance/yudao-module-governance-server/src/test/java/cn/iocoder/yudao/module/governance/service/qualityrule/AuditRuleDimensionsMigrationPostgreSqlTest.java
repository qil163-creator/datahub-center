package cn.iocoder.yudao.module.governance.service.qualityrule;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlRegistry;
import cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionRegistry;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.module.governance.controller.admin.qualityrule.AuditRuleController;
import cn.iocoder.yudao.module.governance.controller.admin.qualityrule.vo.AuditRulePageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.qualityrule.vo.AuditRuleSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.mysql.categorycleanrule.CategoryCleanRuleMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.qualityrule.AuditRuleDimensionMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.qualityrule.AuditRuleMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.qualityrule.CleanRuleMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardcodeitem.StandardCodeItemMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardcodeset.StandardCodeSetMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardelement.StandardElementMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardelement.StandardElementRuleMapper;
import cn.iocoder.yudao.module.governance.service.standardelement.StandardCatalogService;
import cn.iocoder.yudao.module.governance.service.standardelement.StandardElementRuleService;
import cn.iocoder.yudao.module.modeling.api.model.ModelRuleContextApi;
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
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.AUDIT_RULE_DIMENSION_NOT_EXISTS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 原始五维度、二十八条规则的独立备份证据验证，不从待测迁移构造期望映射。 */
@Testcontainers(disabledWithoutDocker = true)
class AuditRuleDimensionsMigrationPostgreSqlTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String MIGRATION = "0203_governance_audit-rule-dimensions.sql";
    private static final String AUDIT = "dh_governance_audit_rule";
    private static final String DIMENSION = "dh_governance_audit_rule_dimension";
    private static final String SEQUENCE = "dh_governance_audit_rule_dimension_seq";
    private static final String QUALITY = "dh_governance_category_data_quality";
    private static final String MARKET = "dh_asset_market_category";
    private static final List<String> UNRELATED = List.of(QUALITY, MARKET, "dh_governance_quality_task",
            "dh_governance_quality_task_instance", "dh_governance_standard_element_version");

    private Connection connection;
    private Oracle oracle;

    @BeforeEach
    void initializeAfter0202() throws Exception {
        try (var input = getClass().getResourceAsStream("/qualityrule/original-audit-dimensions.json")) {
            assertNotNull(input);
            oracle = JsonUtils.parseObject(new String(input.readAllBytes(), StandardCharsets.UTF_8), Oracle.class);
        }
        assertEquals(5, oracle.dimensions().size());
        assertEquals(28, oracle.rules().size());
        connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        execute("""
                DROP SCHEMA IF EXISTS "datahub-center" CASCADE;
                CREATE SCHEMA "datahub-center";
                SET search_path TO "datahub-center";
                CREATE TABLE dh_governance_audit_rule (
                    id bigint PRIMARY KEY, name varchar(100) NOT NULL, dimension_id bigint,
                    code varchar(32), strategy_key varchar(64), rule_level smallint NOT NULL,
                    description varchar(500), use_case varchar(500), example varchar(2000), status smallint NOT NULL,
                    creator varchar(64) NOT NULL DEFAULT 'fixture-owner',
                    updater varchar(64) NOT NULL DEFAULT 'migration:0201',
                    create_time timestamp NOT NULL DEFAULT TIMESTAMP '2025-01-01 00:00:00',
                    update_time timestamp NOT NULL DEFAULT TIMESTAMP '2025-02-01 00:00:00',
                    deleted smallint NOT NULL DEFAULT 0
                );
                COMMENT ON COLUMN dh_governance_audit_rule.dimension_id IS '0201 历史分类置空';
                CREATE TABLE dh_governance_category_data_quality (
                    id bigint PRIMARY KEY, parent_id bigint NOT NULL, category_name varchar(128), category_code varchar(128),
                    sort integer, status smallint, remark text, creator varchar(64), deleted smallint
                );
                INSERT INTO dh_governance_category_data_quality
                SELECT 1000+n, CASE WHEN n<=8 THEN 0 ELSE 1001+(n-9)%8 END,
                    '业务类目' || n, '0,' || (1000+n), n,0,'保留质量类目说明' || n,'migration:0201',0
                FROM generate_series(1,95) n;
                CREATE TABLE dh_asset_market_category (
                    id bigint PRIMARY KEY, parent_id bigint, name varchar(50), sort integer,
                    status smallint, description varchar(256), creator varchar(64), deleted smallint
                );
                INSERT INTO dh_asset_market_category
                SELECT 2000+n, CASE WHEN n<=11 THEN 0 ELSE 2001+(n-12)%11 END,
                    '集市类目' || n,n,0,'保留集市类目说明' || n,'migration:0202',0 FROM generate_series(1,78) n;
                CREATE TABLE dh_governance_quality_task (
                    id bigint PRIMARY KEY, task_category varchar(128), owner_user_id bigint, rule_ids varchar(512)
                );
                INSERT INTO dh_governance_quality_task VALUES (301,'ASSET',501,'107,108'),(302,NULL,502,'306');
                CREATE TABLE dh_governance_quality_task_instance (
                    id bigint PRIMARY KEY, definition_snapshot text, execution_user_id bigint
                );
                INSERT INTO dh_governance_quality_task_instance VALUES
                    (401,'{ "dimensionId": 5, "ruleCode": "107", "strategyKey": "CHARACTER_VALIDATION" }',501);
                CREATE TABLE dh_governance_standard_element_version (
                    id bigint PRIMARY KEY, snapshot text, rule_bindings_snapshot jsonb
                );
                INSERT INTO dh_governance_standard_element_version VALUES
                    (601,'{ "dimensionId": 2, "version": 3 }','[{"templateCode":"108","dimensionName":"完整性"}]');
                """);
        try (var insert = connection.prepareStatement("INSERT INTO " + AUDIT
                + " (id,name,code,strategy_key,rule_level,description,use_case,example,status) VALUES (?,?,?,?,?,?,?,?,0)")) {
            for (OriginalRule rule : oracle.rules()) {
                insert.setLong(1, 50000 + Long.parseLong(rule.code()));
                insert.setString(2, rule.name());
                insert.setString(3, rule.code());
                insert.setString(4, rule.strategyKey());
                insert.setInt(5, rule.ruleLevel());
                insert.setString(6, "迁移前规则说明：" + rule.name());
                insert.setString(7, "迁移前使用场景：" + rule.code());
                insert.setString(8, "迁移前示例：" + rule.strategyKey());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    @AfterEach
    void closeConnection() throws SQLException {
        if (connection != null) connection.close();
    }

    @Test
    void restoresExactOriginalRelationshipsAndPreservesRuleContentsAndOtherDomains() throws Exception {
        List<String> ruleContents = stableRuleContents();
        Map<String, List<String>> unrelated = rowsForTables(UNRELATED);

        migrate();

        assertExactDimensions();
        assertOriginalRuleMapping();
        assertEquals(ruleContents, stableRuleContents());
        assertEquals(unrelated, rowsForTables(UNRELATED));
        assertEquals(95, scalar("SELECT count(*) FROM " + QUALITY));
        assertEquals(78, scalar("SELECT count(*) FROM " + MARKET));
        assertEquals(28, scalar("SELECT count(*) FROM " + AUDIT + " WHERE updater='migration:0203'"
                + " AND update_time > TIMESTAMP '2025-02-01 00:00:00'"));
        assertEquals("NO", nullable());
        assertEquals("r", strings("SELECT confdeltype::text FROM pg_constraint WHERE conrelid='" + AUDIT
                + "'::regclass AND confrelid='" + DIMENSION + "'::regclass AND contype='f'").getFirst());
        SQLException nullFailure = assertThrows(SQLException.class,
                () -> execute("UPDATE " + AUDIT + " SET dimension_id=NULL WHERE code='107'"));
        assertEquals("23502", nullFailure.getSQLState());
        SQLException deleteFailure = assertThrows(SQLException.class,
                () -> execute("DELETE FROM " + DIMENSION + " WHERE code='D05'"));
        assertEquals("23503", deleteFailure.getSQLState());
    }

    @Test
    void realServicesKeepFiveDimensionCountsAndTemplatesIndependentOfBothCategoryTrees() throws Exception {
        migrate();
        Services services = actualServices();
        assertEquals(List.of("准确性", "规范性", "时效性", "一致性", "完整性"),
                services.dimensions().getDimensionList().stream().map(row -> row.getName()).toList());
        assertDimensionPages(services);
        var templateService = new StandardElementRuleService(mock(StandardElementRuleMapper.class), mock(StandardElementMapper.class),
                services.mapper(), mock(CleanRuleMapper.class), mock(CategoryCleanRuleMapper.class), services.dimensions(),
                new AuditSqlRegistry(), new CleanFunctionRegistry(), mock(DbSwitchRuntime.class), mock(StandardCatalogService.class),
                mock(ModelRuleContextApi.class), mock(SecurityFrameworkService.class), mock(StandardCodeItemMapper.class), mock(StandardCodeSetMapper.class));
        Map<String, String> names = oracle.dimensions().stream().collect(Collectors.toMap(Dimension::code, Dimension::name));
        Map<String, String> expected = oracle.rules().stream().collect(Collectors.toMap(OriginalRule::code,
                rule -> names.get(rule.dimensionCode())));
        assertEquals(expected, templateService.templates("AUDIT").stream().collect(Collectors.toMap(
                row -> row.getCode(), row -> row.getDimensionName())));
        var beforeTemplates = JsonUtils.toJsonString(templateService.templates("AUDIT"));
        List<String> dimensions = rows(DIMENSION);
        List<String> rules = rows(AUDIT);
        execute("DELETE FROM " + QUALITY);
        execute("UPDATE " + MARKET + " SET name='集市独立维护' WHERE id=2001");

        assertEquals(dimensions, rows(DIMENSION));
        assertEquals(rules, rows(AUDIT));
        assertDimensionPages(services);
        assertEquals(beforeTemplates, JsonUtils.toJsonString(templateService.templates("AUDIT")));
    }

    @Test
    void qualityCategoryIdCannotPassDimensionServiceOrForeignKeyValidation() throws Exception {
        migrate();
        Services services = actualServices();
        assertNull(services.dimensions().getDimension(1001L));
        var page = new AuditRulePageReqVO();
        page.setDimensionId(1001L);
        assertEquals(0L, services.rules().getAuditRulePage(page).getTotal());
        var save = new AuditRuleSaveReqVO();
        save.setName("不能使用质量类目的新规则");
        save.setDimensionId(1001L);
        var create = assertThrows(ServiceException.class, () -> services.rules().createAuditRule(save));
        assertEquals(AUDIT_RULE_DIMENSION_NOT_EXISTS.getCode(), create.getCode());
        save.setId(50107L);
        var update = assertThrows(ServiceException.class, () -> services.rules().updateAuditRule(save));
        assertEquals(AUDIT_RULE_DIMENSION_NOT_EXISTS.getCode(), update.getCode());
        SQLException foreignKey = assertThrows(SQLException.class,
                () -> execute("UPDATE " + AUDIT + " SET dimension_id=1001 WHERE code='107'"));
        assertEquals("23503", foreignKey.getSQLState());
        assertOriginalRuleMapping();
    }

    @Test
    void dimensionEndpointAndRuleResponsesUseIndependentNamesAndFlatContract() throws Exception {
        migrate();
        Services services = actualServices();
        var controller = new AuditRuleController();
        ReflectionTestUtils.setField(controller, "auditRuleService", services.rules());
        ReflectionTestUtils.setField(controller, "auditRuleDimensionService", services.dimensions());
        ReflectionTestUtils.setField(controller, "auditSqlRegistry", new AuditSqlRegistry());
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(get("/governance/audit-rule/dimension-list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(5))
                .andExpect(jsonPath("$.data[0].name").value("准确性"))
                .andExpect(jsonPath("$.data[0].code").value("D05"))
                .andExpect(jsonPath("$.data[0].sort").value(0))
                .andExpect(jsonPath("$.data[0].status").value(0))
                .andExpect(jsonPath("$.data[0].parentId").doesNotExist());
        assertEquals("准确性", controller.getAuditRule(50107L).getCheckedData().getDimensionName());
        var page = new AuditRulePageReqVO();
        page.setPageSize(100);
        var response = controller.getAuditRulePage(page).getCheckedData();
        assertEquals(28L, response.getTotal());
        assertEquals(Map.of("一致性",7L,"完整性",12L,"规范性",3L,"时效性",1L,"准确性",5L),
                response.getList().stream().collect(Collectors.groupingBy(row -> row.getDimensionName(), Collectors.counting())));
    }

    @Test
    void replayPreservesManuallyMaintainedDimensionFieldsAndValidRuleAssignments() throws Exception {
        migrate();
        execute("UPDATE " + DIMENSION + " SET name='人工维护名称',sort=17,status=1,remark='人工备注',"
                + "updater='manual-editor' WHERE code='D05'");
        execute("UPDATE " + AUDIT + " SET dimension_id=(SELECT id FROM " + DIMENSION + " WHERE code='D03'),"
                + "description='人工维护内容',updater='manual-editor' WHERE code='107'");
        Map<String, List<String>> before = allRows();
        SequenceState beforeSequence = sequenceState();

        migrate();

        assertEquals(before, allRows());
        assertEquals(beforeSequence, sequenceState());
    }

    @Test
    void unknownUnclassifiedRuleRollsBackAllRestorationsAndNewSchema() throws Exception {
        execute("INSERT INTO " + AUDIT + " (id,name,code,rule_level,status) VALUES (59999,'未知历史规则','UNKNOWN',1,0)");
        assertFirstMigrationRejectedWithoutChanges();
    }

    @Test
    void knownCodeWithout0201RecoveryMarkerIsNotGuessed() throws Exception {
        execute("UPDATE " + AUDIT + " SET updater='manual-editor' WHERE code='107'");
        assertFirstMigrationRejectedWithoutChanges();
    }

    @Test
    void nonnullBusinessCategoryReferenceCannotBecomeIndependentDimensionByAccident() throws Exception {
        execute("UPDATE " + AUDIT + " SET dimension_id=1001 WHERE code='107'");
        assertFirstMigrationRejectedWithoutChanges();
    }

    @Test
    void lateRuleUpdateFailureRollsBackDimensionSeedsSequenceAndAllRuleChanges() throws Exception {
        execute("CREATE SEQUENCE " + SEQUENCE + " START 10000");
        execute("ALTER TABLE " + AUDIT + " ADD CONSTRAINT fixture_reject_restoration CHECK (dimension_id IS NULL)");
        Map<String, List<String>> before = rowsForTables(withAudit());
        SequenceState beforeSequence = sequenceState();

        SQLException failure = assertThrows(SQLException.class, this::migrate);
        assertEquals("23514", failure.getSQLState());
        execute("ROLLBACK");

        assertEquals(before, rowsForTables(withAudit()));
        assertEquals(beforeSequence, sequenceState());
        assertEquals("YES", nullable());
        assertEquals(0, scalar("SELECT count(*) FROM pg_class WHERE oid=to_regclass('" + DIMENSION + "')"));
        execute("ALTER TABLE " + AUDIT + " DROP CONSTRAINT fixture_reject_restoration");
        migrate();
        assertOriginalRuleMapping();
        assertEquals(10000, scalar("SELECT min(id) FROM " + DIMENSION));
        assertEquals(10004, scalar("SELECT max(id) FROM " + DIMENSION));
    }

    private void assertFirstMigrationRejectedWithoutChanges() throws Exception {
        Map<String, List<String>> before = rowsForTables(withAudit());
        SQLException failure = assertThrows(SQLException.class, this::migrate);
        assertEquals("P0001", failure.getSQLState());
        execute("ROLLBACK");
        assertEquals(before, rowsForTables(withAudit()));
        assertEquals("YES", nullable());
        assertEquals(0, scalar("SELECT count(*) FROM pg_class WHERE oid IN (to_regclass('" + DIMENSION
                + "'),to_regclass('" + SEQUENCE + "'))"));
    }

    private void assertExactDimensions() throws SQLException {
        List<Dimension> actual = new ArrayList<>();
        try (var statement = connection.createStatement(); var rs = statement.executeQuery(
                "SELECT code,name,sort,status,remark FROM " + DIMENSION + " ORDER BY sort")) {
            while (rs.next()) actual.add(new Dimension(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getString(5)));
        }
        assertEquals(oracle.dimensions().stream().sorted(Comparator.comparingInt(Dimension::sort)).toList(), actual);
        assertEquals(5, scalar("SELECT count(*) FROM " + DIMENSION + " WHERE deleted=0 AND creator='migration:0203'"));
    }

    private void assertOriginalRuleMapping() throws SQLException {
        List<String> expected = oracle.rules().stream().map(rule -> rule.code() + ":" + rule.dimensionCode()).sorted().toList();
        assertEquals(expected, strings("SELECT rule.code || ':' || dimension.code FROM " + AUDIT
                + " rule JOIN " + DIMENSION + " dimension ON dimension.id=rule.dimension_id ORDER BY rule.code"));
    }

    private void assertDimensionPages(Services services) {
        Map<String, Long> counts = Map.of("D01", 7L, "D02", 12L, "D03", 3L, "D04", 1L, "D05", 5L);
        for (var dimension : services.dimensions().getDimensionList()) {
            var request = new AuditRulePageReqVO();
            request.setDimensionId(dimension.getId());
            request.setPageSize(3);
            request.setSortField("id");
            request.setSortOrder("asc");
            List<String> codes = new ArrayList<>();
            long expectedTotal = counts.get(dimension.getCode());
            for (int pageNo = 1; pageNo <= (expectedTotal + 2) / 3; pageNo++) {
                request.setPageNo(pageNo);
                var page = services.rules().getAuditRulePage(request);
                assertEquals(expectedTotal, page.getTotal());
                assertTrue(page.getList().stream().allMatch(rule -> dimension.getId().equals(rule.getDimensionId())));
                page.getList().forEach(rule -> codes.add(rule.getCode()));
            }
            assertEquals(oracle.rules().stream().filter(rule -> dimension.getCode().equals(rule.dimensionCode()))
                    .map(OriginalRule::code).sorted().toList(), codes);
        }
    }

    private Services actualServices() {
        String url = POSTGRES.getJdbcUrl();
        var source = new UnpooledDataSource("org.postgresql.Driver", url + (url.contains("?") ? "&" : "?")
                + "currentSchema=datahub-center", POSTGRES.getUsername(), POSTGRES.getPassword());
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setDefaultStatementTimeout(10);
        config.setEnvironment(new Environment("audit-dimension-migration", new JdbcTransactionFactory(), source));
        var pagination = new MybatisPlusInterceptor();
        pagination.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        config.addInterceptor(pagination);
        config.addMapper(AuditRuleMapper.class);
        config.addMapper(AuditRuleDimensionMapper.class);
        var sessions = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        var dimensions = new AuditRuleDimensionServiceImpl();
        ReflectionTestUtils.setField(dimensions, "auditRuleDimensionMapper", sessions.getMapper(AuditRuleDimensionMapper.class));
        var rules = new AuditRuleServiceImpl();
        ReflectionTestUtils.setField(rules, "auditRuleMapper", sessions.getMapper(AuditRuleMapper.class));
        ReflectionTestUtils.setField(rules, "auditRuleDimensionService", dimensions);
        ReflectionTestUtils.setField(rules, "auditSqlRegistry", new AuditSqlRegistry());
        return new Services(rules, dimensions, sessions.getMapper(AuditRuleMapper.class));
    }

    private List<String> stableRuleContents() throws SQLException {
        return strings("SELECT (to_jsonb(record_row)-ARRAY['dimension_id','updater','update_time'])::text FROM "
                + AUDIT + " record_row ORDER BY id");
    }

    private List<String> withAudit() {
        var tables = new ArrayList<>(UNRELATED);
        tables.add(AUDIT);
        return tables;
    }

    private Map<String, List<String>> allRows() throws SQLException {
        var tables = withAudit();
        tables.add(DIMENSION);
        return rowsForTables(tables);
    }

    private Map<String, List<String>> rowsForTables(List<String> tables) throws SQLException {
        var result = new LinkedHashMap<String, List<String>>();
        for (String table : tables) result.put(table, rows(table));
        return result;
    }

    private List<String> rows(String table) throws SQLException {
        return strings("SELECT to_jsonb(record_row)::text FROM " + table + " record_row ORDER BY id");
    }

    private String nullable() throws SQLException {
        return strings("SELECT is_nullable FROM information_schema.columns WHERE table_schema='datahub-center'"
                + " AND table_name='dh_governance_audit_rule' AND column_name='dimension_id'").getFirst();
    }

    private SequenceState sequenceState() throws SQLException {
        try (var statement = connection.createStatement(); var rs = statement.executeQuery("SELECT last_value,is_called FROM " + SEQUENCE)) {
            assertTrue(rs.next());
            return new SequenceState(rs.getLong(1), rs.getBoolean(2));
        }
    }

    private List<String> strings(String sql) throws SQLException {
        var result = new ArrayList<String>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) result.add(rs.getString(1));
        }
        return result;
    }

    private long scalar(String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    private void migrate() throws Exception {
        String relative = "sql/postgresql/migrations/" + MIGRATION;
        for (Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath(); root != null; root = root.getParent()) {
            if (Files.isRegularFile(root.resolve(relative))) {
                execute(Files.readString(root.resolve(relative)));
                return;
            }
        }
        throw new IllegalStateException("缺少编号迁移：" + relative);
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(30);
            statement.execute(sql);
        }
    }

    public record Oracle(List<Dimension> dimensions, List<OriginalRule> rules) { }
    public record Dimension(String code, String name, int sort, int status, String remark) { }
    public record OriginalRule(String code, String name, String strategyKey, int ruleLevel, String dimensionCode) { }
    private record SequenceState(long lastValue, boolean called) { }
    private record Services(AuditRuleServiceImpl rules, AuditRuleDimensionServiceImpl dimensions, AuditRuleMapper mapper) { }
}
