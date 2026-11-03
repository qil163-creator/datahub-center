package cn.iocoder.yudao.module.governance.service.categorydatameta;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 真实 PostgreSQL 验证水利分类替换、引用解除、完整层级及事务回滚。 */
@Testcontainers(disabledWithoutDocker = true)
class WaterStandardCategoriesMigrationPostgreSqlTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String MIGRATION = "0200_governance_water-standard-categories.sql";
    private static final String CATEGORY = "dh_governance_category_datameta";
    private static final String SEQUENCE = "dh_foundation_category_datameta_seq";
    private static final List<String> BUSINESS_TABLES = List.of(
            "dh_governance_standard_doc", "dh_governance_standard_element",
            "dh_governance_standard_code_set", "dh_governance_standard_term");
    private static final List<String> SNAPSHOT_TABLES = List.of(
            "dh_governance_standard_element_version", "dh_governance_standard_object_version");

    // 从用户五张截图独立转录，不从待测迁移读取预期名称或说明。
    private static final List<RootCategory> EXPECTED = List.of(
            new RootCategory("基础通用", "归集水利行业通用基础、数据治理和共享交换方面的标准文件。",
                    List.of("术语符号与计量", "分类编码与标识", "数据元与指标", "元数据与目录", "交换与共享")),
            new RootCategory("水文监测与预报", "归集水文站网、测验监测、资料整编和预报分析方面的标准文件。",
                    List.of("水文站网与测站", "水文测验与监测", "水情编码与报送", "水文资料整编", "水文预报与分析")),
            new RootCategory("水资源管理", "归集水资源调查、取用水监管、配置调度和节约用水方面的标准文件。",
                    List.of("调查评价", "取用水许可", "计量监测", "配置调度", "节约用水", "地下水管理")),
            new RootCategory("水利工程", "归集水利工程勘测设计、建设施工、安全监测和运行维护方面的标准文件。",
                    List.of("勘测设计", "建设施工", "质量验收", "安全监测", "运行调度", "维修养护")),
            new RootCategory("水旱灾害防御", "归集洪水、干旱、山洪灾害的监测预警、调度和应急处置方面的标准文件。",
                    List.of("洪水监测预报", "风险预警", "防洪调度", "山洪防治", "抗旱管理", "应急处置")),
            new RootCategory("河湖与水生态", "归集河湖空间管理、水域监管、水生态保护和生态流量方面的标准文件。",
                    List.of("河湖名录与岸线", "河湖长制", "水域监管", "生态流量", "水生态", "水质评价")),
            new RootCategory("农村水利", "归集灌溉排水、灌区建设、农村供水和农业节水方面的标准文件。",
                    List.of("灌溉排水", "灌区管理", "农村供水", "泵站运行", "农业节水")),
            new RootCategory("水土保持", "归集水土流失调查、方案管理、监测评价和治理工程方面的标准文件。",
                    List.of("调查规划", "方案与验收", "监测评价", "治理工程", "遥感监管")),
            new RootCategory("智慧水利", "归集水利信息化、数据资源、空间信息、物联网和网络安全方面的标准文件。",
                    List.of("信息化建设", "数据资源", "地理信息与遥感", "自动化与物联网", "数据服务", "网络安全")),
            new RootCategory("水行政与综合管理", "归集水行政许可、执法监督、规划项目、统计档案和移民管理方面的标准文件。",
                    List.of("行政许可", "执法监督", "规划项目", "水利统计", "档案管理", "水库移民")));

    private Connection connection;

    @BeforeEach
    void initializePreMigrationSchema() throws Exception {
        connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        // 仅操作本测试自建容器；夹具保留迁移前真实列、文档外键和后续装载的生产触发器。
        execute("""
                DROP SCHEMA IF EXISTS "datahub-center" CASCADE;
                CREATE SCHEMA "datahub-center";
                SET search_path TO "datahub-center";
                CREATE SEQUENCE dh_foundation_category_datameta_seq START 5000;
                CREATE TABLE dh_governance_category_datameta (
                    id bigint PRIMARY KEY, parent_id bigint NOT NULL DEFAULT 0,
                    category_name varchar(128) NOT NULL, category_code varchar(128) NOT NULL,
                    sort integer NOT NULL DEFAULT 0, status smallint NOT NULL DEFAULT 0, remark text,
                    creator varchar(64), updater varchar(64),
                    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP, deleted smallint NOT NULL DEFAULT 0
                );
                INSERT INTO dh_governance_category_datameta
                    (id, parent_id, category_name, category_code, sort, status, creator, updater, deleted) VALUES
                    (10, 0, '旧有效根类', '0,10', 1, 0, 'migration:0195', 'old-editor', 0),
                    (20, 10, '旧已删除子类', '0,10,20', 2, 0, 'old-editor', 'old-editor', 1),
                    (30, 10, '旧有效子类', '0,10,30', 3, 0, 'old-editor', 'old-editor', 0),
                    (9000, 0, '旧停用根类', '0,9000', 4, 1, 'old-editor', 'old-editor', 0);
                CREATE TABLE dh_governance_standard_doc (
                    id bigint PRIMARY KEY, category_id bigint REFERENCES dh_governance_category_datameta(id),
                    name_cn varchar(255), file_id bigint, version_no varchar(64),
                    creator varchar(64) NOT NULL DEFAULT 'original-author',
                    create_time timestamp NOT NULL DEFAULT TIMESTAMP '2025-01-01 00:00:00',
                    updater varchar(64) NOT NULL DEFAULT 'original-editor',
                    update_time timestamp NOT NULL DEFAULT TIMESTAMP '2025-02-01 00:00:00',
                    deleted smallint NOT NULL DEFAULT 0
                );
                CREATE TABLE dh_governance_standard_element (
                    id bigint PRIMARY KEY, category_id bigint, document_id bigint, code_set_id bigint,
                    name varchar(255), lifecycle_status varchar(32), revision bigint NOT NULL DEFAULT 0,
                    creator varchar(64) NOT NULL DEFAULT 'original-author',
                    create_time timestamp NOT NULL DEFAULT TIMESTAMP '2025-01-01 00:00:00',
                    updater varchar(64) NOT NULL DEFAULT 'original-editor',
                    update_time timestamp NOT NULL DEFAULT TIMESTAMP '2025-02-01 00:00:00',
                    deleted smallint NOT NULL DEFAULT 0
                );
                CREATE TABLE dh_governance_standard_code_set (
                    id bigint PRIMARY KEY, category_id bigint, document_id bigint,
                    name varchar(255), type varchar(64), revision bigint NOT NULL DEFAULT 0,
                    creator varchar(64) NOT NULL DEFAULT 'original-author',
                    create_time timestamp NOT NULL DEFAULT TIMESTAMP '2025-01-01 00:00:00',
                    updater varchar(64) NOT NULL DEFAULT 'original-editor',
                    update_time timestamp NOT NULL DEFAULT TIMESTAMP '2025-02-01 00:00:00',
                    deleted smallint NOT NULL DEFAULT 0
                );
                CREATE TABLE dh_governance_standard_term (
                    id bigint PRIMARY KEY, category_id bigint, related_element_id bigint,
                    name varchar(255), definition text,
                    creator varchar(64) NOT NULL DEFAULT 'original-author',
                    create_time timestamp NOT NULL DEFAULT TIMESTAMP '2025-01-01 00:00:00',
                    updater varchar(64) NOT NULL DEFAULT 'original-editor',
                    update_time timestamp NOT NULL DEFAULT TIMESTAMP '2025-02-01 00:00:00',
                    deleted smallint NOT NULL DEFAULT 0
                );
                INSERT INTO dh_governance_standard_doc (id, category_id, name_cn, file_id, version_no, deleted) VALUES
                    (101, 10, '有效标准文件', 8001, 'v1', 0),
                    (102, 20, '已删除标准文件', 8002, 'v2', 1),
                    (103, NULL, '未分类标准文件', 8003, 'v3', 0);
                INSERT INTO dh_governance_standard_code_set (id, category_id, document_id, name, type, revision, deleted) VALUES
                    (301, 10, 101, '有效代码表', 'ACTIVE_CODES', 4, 0),
                    (302, 20, 102, '已删除代码表', 'DELETED_CODES', 9, 1),
                    (303, NULL, 101, '未分类代码表', 'UNBOUND_CODES', 15, 0);
                INSERT INTO dh_governance_standard_element
                    (id, category_id, document_id, code_set_id, name, lifecycle_status, revision, deleted) VALUES
                    (201, 10, 101, 301, '有效数据元', 'PUBLISHED', 7, 0),
                    (202, 20, 102, 302, '已删除数据元', 'DEPRECATED', 11, 1),
                    (203, NULL, 101, 301, '未分类数据元', 'DRAFT', 19, 0);
                INSERT INTO dh_governance_standard_term (id, category_id, related_element_id, name, definition, deleted) VALUES
                    (401, 10, 201, '有效术语', '保留有效术语定义', 0),
                    (402, 20, 202, '已删除术语', '保留已删除术语定义', 1),
                    (403, NULL, 203, '未分类术语', '保留未分类术语定义', 0);
                CREATE TABLE dh_governance_standard_element_version (
                    id bigint PRIMARY KEY, element_id bigint, version integer, snapshot text
                );
                CREATE TABLE dh_governance_standard_object_version (
                    id bigint PRIMARY KEY, object_type varchar(32), object_id bigint, version integer, snapshot text
                );
                INSERT INTO dh_governance_standard_element_version VALUES
                    (701, 201, 1, '{ "categoryId": 10, "categoryName": "旧有效根类", "revision": 7 }'),
                    (702, 202, 2, '{ "categoryId": 20, "categoryName": "旧已删除子类", "revision": 11 }');
                INSERT INTO dh_governance_standard_object_version VALUES
                    (801, 'DOC', 101, 1, '{ "categoryId": 10, "nameCn": "有效标准文件" }'),
                    (802, 'CODE_SET', 302, 2, '{ "categoryId": 20, "revision": 9 }');
                """);
        // 复用前置迁移的真实引用、修订触发器，避免夹具伪造双重递增语义。
        execute(dependencySection("0191_governance_standard-element-workbench.sql",
                "CREATE OR REPLACE FUNCTION standard_workbench_reference_guard()",
                "CREATE OR REPLACE FUNCTION standard_workbench_delete_guard()"));
        execute(dependencySection("0191_governance_standard-element-workbench.sql",
                "CREATE OR REPLACE FUNCTION standard_workbench_revision_guard()", "-- 将已实现"));
        execute(dependencySection("0199_governance_standard-document-workbench.sql",
                "CREATE OR REPLACE FUNCTION standard_doc_category_reference_guard()", "-- 使用现有字典协议"));
    }

    @AfterEach
    void closeConnection() throws SQLException {
        if (connection != null) connection.close();
    }

    @Test
    void replacesAllOldCategoriesWithExactScreenshotHierarchyAndFreshIds() throws Exception {
        migrate();

        assertEquals(66, scalar("SELECT count(*) FROM " + CATEGORY));
        assertEquals(0, scalar("SELECT count(*) FROM " + CATEGORY + " WHERE id IN (10,20,30,9000)"));
        assertEquals(9001, scalar("SELECT min(id) FROM " + CATEGORY));
        assertEquals(9066, scalar("SELECT max(id) FROM " + CATEGORY));
        assertExactHierarchy();
        assertEquals(new SequenceState(9066, true), sequenceState());
        assertEquals(9067, scalar("SELECT nextval('" + SEQUENCE + "')"));
    }

    @Test
    void detachesAllFourKindsIncludingDeletedRowsWithoutDeletingBusinessOrRewritingSnapshots() throws Exception {
        Map<String, List<String>> stableBusiness = businessRowsWithoutCategoryEdit();
        Map<String, List<String>> historicalSnapshots = rowsForTables(SNAPSHOT_TABLES);
        Map<String, List<String>> unclassified = new LinkedHashMap<>();
        for (String table : BUSINESS_TABLES) {
            unclassified.put(table, queryStrings("SELECT to_jsonb(record_row)::text FROM " + table
                    + " record_row WHERE category_id IS NULL ORDER BY id"));
        }

        migrate();

        assertEquals(stableBusiness, businessRowsWithoutCategoryEdit());
        assertEquals(historicalSnapshots, rowsForTables(SNAPSHOT_TABLES), "历史快照中的旧编号与文本不可重写");
        for (String table : BUSINESS_TABLES) {
            assertEquals(3, scalar("SELECT count(*) FROM " + table), table);
            assertEquals(0, scalar("SELECT count(*) FROM " + table + " WHERE category_id IS NOT NULL"), table);
            assertEquals(2, scalar("SELECT count(*) FROM " + table
                    + " WHERE updater='migration:0200' AND update_time > TIMESTAMP '2025-02-01 00:00:00'"), table);
            assertEquals(unclassified.get(table), queryStrings("SELECT to_jsonb(record_row)::text FROM " + table
                    + " record_row WHERE updater='original-editor' ORDER BY id"), "原本未分类的行不应被更新：" + table);
        }
        assertEquals(List.of("201:8", "202:12", "203:19"), queryStrings(
                "SELECT id || ':' || revision FROM dh_governance_standard_element ORDER BY id"));
        assertEquals(List.of("301:5", "302:10", "303:15"), queryStrings(
                "SELECT id || ':' || revision FROM dh_governance_standard_code_set ORDER BY id"));
    }

    @Test
    void respectsSequenceHighWaterMarkAboveExistingIds() throws Exception {
        execute("SELECT setval('" + SEQUENCE + "', 20000, false)");

        migrate();

        assertEquals(20001, scalar("SELECT min(id) FROM " + CATEGORY));
        assertEquals(20066, scalar("SELECT max(id) FROM " + CATEGORY));
        assertEquals(new SequenceState(20066, true), sequenceState());
        assertEquals(20067, scalar("SELECT nextval('" + SEQUENCE + "')"));
    }

    @Test
    void replayIsRejectedBeforeDetachingNewReferencesOrChangingSequence() throws Exception {
        migrate();
        long categoryId = scalar("SELECT id FROM " + CATEGORY + " WHERE category_name='基础通用'");
        for (String table : BUSINESS_TABLES) {
            execute("UPDATE " + table + " SET category_id=" + categoryId + " WHERE deleted=0");
        }
        Map<String, List<String>> before = allRows();
        SequenceState sequenceBefore = sequenceState();

        SQLException failure = assertThrows(SQLException.class, this::migrate);
        assertEquals("P0001", failure.getSQLState());
        assertTrue(failure.getMessage().contains("0200"));
        execute("ROLLBACK");

        assertEquals(before, allRows());
        assertEquals(sequenceBefore, sequenceState());
    }

    @Test
    void seedFailureRollsBackDetachedReferencesRevisionsOldCategoriesAndSequence() throws Exception {
        // 在靠后的根类插入时制造约束失败，确保已发生的解除引用和删除也在事务中回滚。
        execute("ALTER TABLE " + CATEGORY
                + " ADD CONSTRAINT fixture_reject_late_seed CHECK (category_name <> '智慧水利')");
        Map<String, List<String>> before = allRows();
        SequenceState sequenceBefore = sequenceState();

        SQLException failure = assertThrows(SQLException.class, this::migrate);
        assertEquals("23514", failure.getSQLState());
        assertTrue(failure.getMessage().contains("fixture_reject_late_seed"));
        execute("ROLLBACK");

        assertEquals(before, allRows());
        assertEquals(sequenceBefore, sequenceState(), "事务式重启失败后应恢复迁移前的序列高水位");
        execute("ALTER TABLE " + CATEGORY + " DROP CONSTRAINT fixture_reject_late_seed");
        migrate();
        assertExactHierarchy();
    }

    @Test
    void invalidSequenceIncrementRejectsMigrationBeforeChangingData() throws Exception {
        execute("ALTER SEQUENCE " + SEQUENCE + " INCREMENT BY 2");
        Map<String, List<String>> before = allRows();
        SequenceState sequenceBefore = sequenceState();

        SQLException failure = assertThrows(SQLException.class, this::migrate);
        assertEquals("P0001", failure.getSQLState());
        execute("ROLLBACK");

        assertEquals(before, allRows());
        assertEquals(sequenceBefore, sequenceState());
        assertEquals(2, scalar("SELECT seqincrement FROM pg_sequence WHERE seqrelid='" + SEQUENCE + "'::regclass"));
    }

    private void assertExactHierarchy() throws SQLException {
        List<CategoryRow> categories = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT id,parent_id,category_name,category_code,sort,remark FROM " + CATEGORY + " ORDER BY sort,id")) {
            while (rows.next()) {
                categories.add(new CategoryRow(rows.getLong("id"), rows.getLong("parent_id"),
                        rows.getString("category_name"), rows.getString("category_code"),
                        rows.getInt("sort"), rows.getString("remark")));
            }
        }
        assertEquals(66, categories.size());
        List<CategoryRow> roots = categories.stream().filter(row -> row.parentId() == 0).toList();
        assertEquals(EXPECTED.stream().map(RootCategory::name).toList(), roots.stream().map(CategoryRow::name).toList());
        int childCount = 0;
        for (int rootSort = 0; rootSort < EXPECTED.size(); rootSort++) {
            RootCategory expected = EXPECTED.get(rootSort);
            CategoryRow root = roots.get(rootSort);
            assertEquals(rootSort, root.sort(), root.name());
            assertEquals(expected.remark(), root.remark(), root.name());
            assertEquals("0," + root.id(), root.code(), root.name());
            List<CategoryRow> children = categories.stream().filter(row -> row.parentId() == root.id()).toList();
            assertEquals(expected.children(), children.stream().map(CategoryRow::name).toList(), root.name());
            for (int childSort = 0; childSort < children.size(); childSort++) {
                CategoryRow child = children.get(childSort);
                assertEquals(childSort, child.sort(), child.name());
                assertEquals("0," + root.id() + "," + child.id(), child.code(), child.name());
                assertEquals("归集水利行业" + child.name() + "相关标准文件，用于标准登记、检索和统一管理。",
                        child.remark(), child.name());
            }
            childCount += children.size();
        }
        assertEquals(56, childCount);
        assertEquals(66, scalar("SELECT count(*) FROM " + CATEGORY
                + " WHERE status=0 AND deleted=0 AND creator='migration:0200' AND updater='migration:0200'"));
    }

    private Map<String, List<String>> businessRowsWithoutCategoryEdit() throws SQLException {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String table : BUSINESS_TABLES) {
            result.put(table, queryStrings("SELECT (to_jsonb(record_row) - ARRAY['category_id','revision','updater','update_time'])::text"
                    + " FROM " + table + " record_row ORDER BY id"));
        }
        return result;
    }

    private Map<String, List<String>> allRows() throws SQLException {
        List<String> tables = new ArrayList<>(BUSINESS_TABLES);
        tables.add(CATEGORY);
        tables.addAll(SNAPSHOT_TABLES);
        return rowsForTables(tables);
    }

    private Map<String, List<String>> rowsForTables(List<String> tables) throws SQLException {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String table : tables) {
            result.put(table, queryStrings("SELECT to_jsonb(record_row)::text FROM " + table + " record_row ORDER BY id"));
        }
        return result;
    }

    private SequenceState sequenceState() throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery(
                "SELECT last_value,is_called FROM " + SEQUENCE)) {
            assertTrue(row.next());
            return new SequenceState(row.getLong(1), row.getBoolean(2));
        }
    }

    private List<String> queryStrings(String sql) throws SQLException {
        List<String> result = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) result.add(rows.getString(1));
        }
        return result;
    }

    private long scalar(String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private void migrate() throws Exception {
        execute(readMigration(MIGRATION));
    }

    private String dependencySection(String migration, String start, String end) throws Exception {
        String sql = readMigration(migration);
        int startIndex = sql.indexOf(start);
        int endIndex = sql.indexOf(end, startIndex);
        assertTrue(startIndex >= 0 && endIndex > startIndex, "前置迁移中的约束区段必须存在：" + migration);
        return sql.substring(startIndex, endIndex);
    }

    private String readMigration(String name) throws Exception {
        String relativePath = "sql/postgresql/migrations/" + name;
        for (Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath(); root != null; root = root.getParent()) {
            if (Files.isRegularFile(root.resolve(relativePath))) return Files.readString(root.resolve(relativePath));
        }
        throw new IllegalStateException("缺少编号迁移：" + relativePath);
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(30);
            statement.execute(sql);
        }
    }

    private record RootCategory(String name, String remark, List<String> children) { }
    private record CategoryRow(long id, long parentId, String name, String code, int sort, String remark) { }
    private record SequenceState(long lastValue, boolean called) { }
}
