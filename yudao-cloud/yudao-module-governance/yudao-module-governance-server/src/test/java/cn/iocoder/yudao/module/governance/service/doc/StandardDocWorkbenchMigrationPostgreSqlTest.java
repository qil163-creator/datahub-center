package cn.iocoder.yudao.module.governance.service.doc;

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
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 在独立 PostgreSQL 容器验证完整 0199 迁移、重放及类目引用约束。 */
@Testcontainers(disabledWithoutDocker = true)
class StandardDocWorkbenchMigrationPostgreSqlTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private Connection connection;

    @BeforeEach
    void initializePreMigrationSchema() throws SQLException {
        connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        // 只重建本测试拥有的容器；保留迁移所需的既有表和历史数据，不预建新增列或约束。
        execute("""
                DROP SCHEMA IF EXISTS "datahub-center" CASCADE;
                CREATE SCHEMA "datahub-center";
                SET search_path TO "datahub-center";
                CREATE TABLE dh_governance_category_datameta (
                    id bigint PRIMARY KEY, deleted smallint NOT NULL DEFAULT 0
                );
                CREATE TABLE dh_governance_standard_doc (
                    id bigint PRIMARY KEY, std_type varchar(32), deleted smallint NOT NULL DEFAULT 0
                );
                CREATE TABLE dh_governance_standard_element (
                    id bigint PRIMARY KEY, document_id bigint, deleted smallint NOT NULL DEFAULT 0
                );
                CREATE TABLE dh_governance_standard_code_set (
                    id bigint PRIMARY KEY, document_id bigint, deleted smallint NOT NULL DEFAULT 0
                );
                CREATE TABLE dh_modeling_logic_model (
                    id bigint PRIMARY KEY, document_id bigint, deleted smallint NOT NULL DEFAULT 0
                );
                CREATE SEQUENCE system_dict_data_seq START 1;
                CREATE TABLE system_dict_data (
                    id bigint PRIMARY KEY, sort integer, label varchar(100), value varchar(100),
                    dict_type varchar(100), status smallint, color_type varchar(32),
                    creator varchar(64), updater varchar(64), deleted smallint NOT NULL DEFAULT 0
                );
                INSERT INTO dh_governance_standard_doc (id, std_type) VALUES (10, 'NATIONAL');
                INSERT INTO system_dict_data (id, label, value, dict_type, status, deleted) VALUES
                    (3518, '保留现行文案', 'CURRENT', 'standard_implement_status', 1, 0),
                    (7001, '历史草案', 'DRAFT', 'standard_implement_status', 0, 1),
                    (9000, '其他字典', 'OTHER', 'other_dictionary', 0, 0);
                CREATE SEQUENCE system_menu_seq START 1;
                CREATE SEQUENCE system_menu_id_seq START 8000;
                CREATE SEQUENCE system_role_menu_seq START 1;
                CREATE TABLE system_menu (
                    id bigint PRIMARY KEY, name varchar(50) NOT NULL, permission varchar(100) NOT NULL DEFAULT '',
                    type smallint NOT NULL, sort integer DEFAULT 0, parent_id bigint DEFAULT 0,
                    path varchar(200), icon varchar(100), component varchar(255), component_name varchar(255),
                    status smallint DEFAULT 0, visible boolean DEFAULT true, keep_alive boolean DEFAULT true,
                    always_show boolean DEFAULT true, creator varchar(64), updater varchar(64),
                    create_time timestamp DEFAULT CURRENT_TIMESTAMP, update_time timestamp DEFAULT CURRENT_TIMESTAMP,
                    deleted smallint NOT NULL DEFAULT 0
                );
                CREATE TABLE system_role_menu (
                    id bigint PRIMARY KEY, role_id bigint, menu_id bigint, tenant_id bigint,
                    creator varchar(64), updater varchar(64), deleted smallint NOT NULL DEFAULT 0
                );
                CREATE TABLE system_tenant_package (
                    id bigint PRIMARY KEY, menu_ids varchar(4096), updater varchar(64),
                    update_time timestamp DEFAULT CURRENT_TIMESTAMP, deleted smallint NOT NULL DEFAULT 0
                );
                INSERT INTO system_menu (id, name, type, sort, parent_id, path, component, component_name) VALUES
                    (501, '标准文件登记', 2, 7, 50, 'original-doc', 'governance/doc/index', 'OriginalDoc'),
                    (9000, '其他入口', 2, 1, 50, 'unrelated', 'other/index', 'Other');
                INSERT INTO system_menu (id, name, permission, type, parent_id, deleted) VALUES
                    (502, '查询', 'governance:doc:query', 3, 501, 0),
                    (503, '新增', 'governance:doc:create', 3, 501, 0),
                    (504, '修改', 'governance:doc:update', 3, 501, 0),
                    (505, '删除', 'governance:doc:delete', 3, 501, 0),
                    (506, '已撤销按钮', 'governance:doc:retired', 3, 501, 1),
                    (507, '其他功能按钮', 'governance:other:query', 3, 501, 0);
                INSERT INTO system_role_menu (id, role_id, menu_id, tenant_id, deleted) VALUES
                    (1001, 41, 501, 9, 0), (1002, 41, 502, 9, 0), (1003, 41, 503, 9, 1),
                    (2001, 42, 501, 9, 0), (2002, 42, 502, 9, 0), (2003, 42, 503, 9, 0),
                    (2004, 42, 504, 9, 0), (2005, 42, 505, 9, 0);
                INSERT INTO system_tenant_package (id, menu_ids, deleted) VALUES
                    (1, '[501,502,9000]', 0), (2, '[501,502,503,504,505]', 0), (3, '[501,502]', 1);
                """);
    }

    @AfterEach
    void closeConnection() throws SQLException {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    void firstExecutionAndReplayPreserveRowsAndDoNotDuplicateDictionaryValues() throws Exception {
        migrate();

        assertEquals("bigint", columnType("category_id"));
        assertEquals("character varying", columnType("version_no"));
        assertEquals("date", columnType("abolish_date"));
        assertEquals(64, scalar("""
                SELECT character_maximum_length FROM information_schema.columns
                WHERE table_schema='datahub-center' AND table_name='dh_governance_standard_doc'
                  AND column_name='version_no'
                """));
        assertEquals(1, scalar("SELECT count(*) FROM dh_governance_standard_doc WHERE id=10 AND category_id IS NULL"));
        assertEquals(4, scalar("""
                SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid
                JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname='datahub-center' AND i.indisvalid AND i.indpred IS NOT NULL
                  AND c.relname IN ('idx_standard_doc_category_live', 'idx_standard_element_document_live',
                                    'idx_standard_code_set_document_live', 'idx_logic_model_document_live')
                """));
        assertEquals(1, scalar("""
                SELECT count(*) FROM pg_constraint
                WHERE conname='fk_standard_doc_category' AND contype='f'
                  AND conrelid='dh_governance_standard_doc'::regclass
                """));

        Map<String, Long> firstIds = activeDictionaryIds();
        assertEquals(4, firstIds.size());
        assertTrue(firstIds.keySet().containsAll(java.util.Set.of("CURRENT", "DRAFT", "REVISING", "TRANSITION")));
        assertTrue(firstIds.get("DRAFT") > 9000, "字典序列须避开既有最大编号");
        execute("""
                UPDATE system_dict_data SET label='用户保留的草案标签', status=1
                WHERE dict_type='standard_implement_status' AND value='DRAFT' AND deleted=0
                """);
        migrate();

        assertEquals(firstIds, activeDictionaryIds(), "重放不得新增或替换已存在的有效字典行");
        assertEquals(0, scalar("""
                SELECT count(*) FROM (
                    SELECT value FROM system_dict_data
                    WHERE dict_type='standard_implement_status' AND deleted=0
                    GROUP BY value HAVING count(*) > 1
                ) duplicate_values
                """));
        assertEquals(1, scalar("""
                SELECT count(*) FROM system_dict_data
                WHERE value='DRAFT' AND dict_type='standard_implement_status' AND deleted=0
                  AND label='用户保留的草案标签' AND status=1
                """));
        assertEquals(1, scalar("SELECT count(*) FROM system_dict_data WHERE id=3518 AND label='保留现行文案' AND status=1"));
        assertEquals(1, scalar("SELECT count(*) FROM system_dict_data WHERE id=7001 AND deleted=1"));
        assertEquals(1, scalar("SELECT count(*) FROM system_dict_data WHERE id=9000 AND dict_type='other_dictionary'"));
    }

    @Test
    void foreignKeyAndReferenceGuardRejectMissingOrDeletedCategories() throws Exception {
        migrate();
        execute("INSERT INTO dh_governance_category_datameta (id, deleted) VALUES (1, 0), (2, 1)");

        assertEquals("P0001", assertThrows(SQLException.class, () -> execute("""
                INSERT INTO dh_governance_standard_doc (id, category_id) VALUES (100, 999)
                """)).getSQLState());
        assertEquals("P0001", assertThrows(SQLException.class, () -> execute("""
                INSERT INTO dh_governance_standard_doc (id, category_id) VALUES (100, 2)
                """)).getSQLState());
        // 已删除文档不触发有效引用检查，仍须由真实外键拒绝不存在的类目。
        assertEquals("23503", assertThrows(SQLException.class, () -> execute("""
                INSERT INTO dh_governance_standard_doc (id, category_id, deleted) VALUES (100, 999, 1)
                """)).getSQLState());

        execute("INSERT INTO dh_governance_standard_doc (id, category_id) VALUES (100, 1)");
        assertEquals("P0001", assertThrows(SQLException.class,
                () -> execute("UPDATE dh_governance_standard_doc SET category_id=2 WHERE id=100")).getSQLState());
        assertEquals(1, scalar("SELECT category_id FROM dh_governance_standard_doc WHERE id=100"));
        assertEquals("23503", assertThrows(SQLException.class,
                () -> execute("DELETE FROM dh_governance_category_datameta WHERE id=1")).getSQLState());
    }

    @Test
    void categorySoftDeleteRequiresAllLiveDocumentsToBeDeletedAndBlocksRestore() throws Exception {
        migrate();
        execute("""
                INSERT INTO dh_governance_category_datameta (id) VALUES (1);
                INSERT INTO dh_governance_standard_doc (id, category_id) VALUES (100, 1), (101, 1);
                """);
        assertThrows(SQLException.class,
                () -> execute("UPDATE dh_governance_category_datameta SET deleted=1 WHERE id=1"));
        execute("UPDATE dh_governance_standard_doc SET deleted=1 WHERE id=100");
        assertThrows(SQLException.class,
                () -> execute("UPDATE dh_governance_category_datameta SET deleted=1 WHERE id=1"));
        assertEquals(0, scalar("SELECT deleted FROM dh_governance_category_datameta WHERE id=1"));

        execute("UPDATE dh_governance_standard_doc SET deleted=1 WHERE id=101");
        execute("UPDATE dh_governance_category_datameta SET deleted=1 WHERE id=1");
        assertEquals(1, scalar("SELECT deleted FROM dh_governance_category_datameta WHERE id=1"));
        assertThrows(SQLException.class,
                () -> execute("UPDATE dh_governance_standard_doc SET deleted=0 WHERE id=100"));
        assertEquals(1, scalar("SELECT deleted FROM dh_governance_standard_doc WHERE id=100"));
    }

    @Test
    void registrationMenuReplayPreservesReadOnlyRoleAndPackagePermissionScopes() throws Exception {
        migrate();
        long registrationId = scalar("""
                SELECT id FROM system_menu WHERE component='governance/doc-register/index' AND deleted=0
                """);
        assertTrue(registrationId > 9000, "新菜单编号须同时避开现存行和两个序列");
        assertEquals(1, scalar("""
                SELECT count(*) FROM system_menu WHERE id=501 AND name='标准文件检索'
                  AND path='original-doc' AND component='governance/doc/index' AND component_name='OriginalDoc'
                """));
        assertEquals(1, scalar("""
                SELECT count(*) FROM system_menu WHERE component='governance/doc-register/index'
                  AND path='doc-register' AND component_name='StandardDocRegister' AND parent_id=50 AND sort=8
                """));
        assertEquals(4, scalar("SELECT count(*) FROM system_menu WHERE parent_id=" + registrationId + " AND type=3 AND deleted=0"));
        assertEquals(1, scalar("SELECT count(*) FROM system_role_menu WHERE role_id=41 AND tenant_id=9 AND deleted=0 AND menu_id=" + registrationId));
        assertEquals(1, scalar("""
                SELECT count(*) FROM system_role_menu grant_row JOIN system_menu menu ON menu.id=grant_row.menu_id
                WHERE grant_row.role_id=41 AND grant_row.tenant_id=9 AND grant_row.deleted=0
                  AND menu.parent_id=%d AND menu.permission='governance:doc:query'
                """.formatted(registrationId)));
        assertEquals(0, scalar("""
                SELECT count(*) FROM system_role_menu grant_row JOIN system_menu menu ON menu.id=grant_row.menu_id
                WHERE grant_row.role_id=41 AND grant_row.deleted=0 AND menu.parent_id=%d
                  AND menu.permission <> 'governance:doc:query'
                """.formatted(registrationId)));
        assertEquals(4, scalar("""
                SELECT count(*) FROM system_role_menu grant_row JOIN system_menu menu ON menu.id=grant_row.menu_id
                WHERE grant_row.role_id=42 AND grant_row.tenant_id=9 AND grant_row.deleted=0 AND menu.parent_id=%d
                """.formatted(registrationId)));
        assertEquals(5, scalar("SELECT jsonb_array_length(menu_ids::jsonb) FROM system_tenant_package WHERE id=1"));
        assertEquals(10, scalar("SELECT jsonb_array_length(menu_ids::jsonb) FROM system_tenant_package WHERE id=2"));
        assertEquals(2, scalar("SELECT jsonb_array_length(menu_ids::jsonb) FROM system_tenant_package WHERE id=3"));
        assertEquals(1, scalar("SELECT count(*) FROM system_tenant_package WHERE id=1 AND menu_ids::jsonb @> '[9000]'::jsonb"));
        assertEquals(0, scalar("""
                SELECT count(*) FROM system_tenant_package package,
                    jsonb_array_elements_text(package.menu_ids::jsonb) entry(value)
                JOIN system_menu menu ON menu.id=entry.value::bigint
                WHERE package.id=1 AND menu.parent_id=%d AND menu.permission <> 'governance:doc:query'
                """.formatted(registrationId)));

        long menuCount = scalar("SELECT count(*) FROM system_menu");
        long roleGrantCount = scalar("SELECT count(*) FROM system_role_menu");
        migrate();

        assertEquals(menuCount, scalar("SELECT count(*) FROM system_menu"));
        assertEquals(roleGrantCount, scalar("SELECT count(*) FROM system_role_menu"));
        assertEquals(registrationId, scalar("SELECT id FROM system_menu WHERE component='governance/doc-register/index' AND deleted=0"));
        assertEquals(0, scalar("""
                SELECT count(*) FROM (
                    SELECT role_id, menu_id, tenant_id FROM system_role_menu WHERE deleted=0
                    GROUP BY role_id, menu_id, tenant_id HAVING count(*) > 1
                ) duplicate_grants
                """));
        assertEquals(5, scalar("SELECT jsonb_array_length(menu_ids::jsonb) FROM system_tenant_package WHERE id=1"));
        assertEquals(10, scalar("SELECT jsonb_array_length(menu_ids::jsonb) FROM system_tenant_package WHERE id=2"));
        assertEquals(2, scalar("SELECT jsonb_array_length(menu_ids::jsonb) FROM system_tenant_package WHERE id=3"));
    }

    private void migrate() throws Exception {
        String relativePath = "sql/postgresql/migrations/0199_governance_standard-document-workbench.sql";
        for (Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath(); root != null; root = root.getParent()) {
            if (Files.isRegularFile(root.resolve(relativePath))) {
                execute(Files.readString(root.resolve(relativePath)));
                return;
            }
        }
        throw new IllegalStateException("缺少标准文件工作台迁移：" + relativePath);
    }

    private Map<String, Long> activeDictionaryIds() throws SQLException {
        Map<String, Long> ids = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("""
                SELECT value, id FROM system_dict_data
                WHERE dict_type='standard_implement_status' AND deleted=0 ORDER BY value
                """)) {
            while (rows.next()) {
                assertNull(ids.put(rows.getString("value"), rows.getLong("id")), "有效字典值不得重复");
            }
        }
        return ids;
    }

    private String columnType(String column) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT data_type FROM information_schema.columns
                WHERE table_schema='datahub-center' AND table_name='dh_governance_standard_doc' AND column_name=?
                """)) {
            statement.setString(1, column);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getString(1);
            }
        }
    }

    private long scalar(String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(30);
            statement.execute(sql);
        }
    }
}
