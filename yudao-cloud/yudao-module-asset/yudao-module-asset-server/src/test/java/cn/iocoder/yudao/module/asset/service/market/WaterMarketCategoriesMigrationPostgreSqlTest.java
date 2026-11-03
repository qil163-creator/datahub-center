package cn.iocoder.yudao.module.asset.service.market;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/** 真实 PostgreSQL 验证集市类目补齐、路径复用、引用保留和事务性序列分配。 */
@Testcontainers(disabledWithoutDocker = true)
class WaterMarketCategoriesMigrationPostgreSqlTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String MIGRATION = "0202_asset_water-market-categories.sql";
    private static final String CATEGORY = "dh_asset_market_category";
    private static final String SEQUENCE = "dh_asset_market_category_seq";
    private Connection connection;

    @BeforeEach
    void createIsolatedSchema() throws Exception {
        connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        execute("""
                DROP SCHEMA IF EXISTS "datahub-center" CASCADE;
                CREATE SCHEMA "datahub-center";
                SET search_path TO "datahub-center";
                CREATE TABLE dh_asset_data_asset (id bigint PRIMARY KEY);
                """);
        // 装载真实前置迁移的类目与挂载表，避免测试夹具省略生产外键及部分唯一索引。
        String dependency = Files.readString(migrations().resolve("0176_asset_market-catalog-and-portal.sql"));
        execute(dependency.substring(dependency.indexOf("CREATE SEQUENCE IF NOT EXISTS dh_asset_market_category_seq;"),
                dependency.indexOf("CREATE SEQUENCE IF NOT EXISTS dh_asset_market_favorite_seq;")));
        execute("ALTER SEQUENCE " + SEQUENCE + " RESTART WITH 1000");
    }

    @AfterEach
    void closeConnection() throws SQLException {
        if (connection != null) connection.close();
    }

    @Test
    void fillsAllSeventyEightExactPathsWithSourceDescriptionsAndSorts() throws Exception {
        migrate();

        Map<String, ExpectedCategory> expected = expectedCategories();
        Map<String, ActualCategory> actual = liveCategories();
        assertEquals(78, expected.size());
        assertEquals(expected.keySet(), actual.keySet());
        for (Map.Entry<String, ExpectedCategory> entry : expected.entrySet()) {
            ActualCategory category = actual.get(entry.getKey());
            assertEquals(entry.getValue().description(), category.description(), entry.getKey());
            assertEquals(entry.getValue().sort(), category.sort(), entry.getKey());
            assertEquals(0, category.status(), entry.getKey());
        }
        assertEquals(11, scalar("SELECT count(*) FROM " + CATEGORY + " WHERE parent_id = 0"));
        assertEquals(67, scalar("SELECT count(*) FROM " + CATEGORY + " WHERE parent_id <> 0"));
        assertEquals(78, scalar("SELECT count(*) FROM " + CATEGORY + " WHERE creator='migration:0202' AND updater='migration:0202'"));
        assertEquals(1000, scalar("SELECT min(id) FROM " + CATEGORY));
        assertEquals(1077, scalar("SELECT max(id) FROM " + CATEGORY));
        assertEquals("1077:true", sequenceSnapshot());
        assertEquals(2, scalar("""
                SELECT count(*) FROM dh_asset_market_category child
                JOIN dh_asset_market_category parent ON parent.id=child.parent_id
                WHERE parent.name='河道水情' AND child.sort=0
                """));
    }

    @Test
    void reusesSameLivePathWithoutChangingMaintenanceOrAssetListing() throws Exception {
        execute("""
                INSERT INTO dh_asset_market_category (id,parent_id,name,description,status,sort,creator,updater)
                VALUES (9000,0,'综合决策','人工维护的根类说明',1,88,'owner','editor'),
                       (9001,9000,'综合态势','人工维护的子类说明',1,99,'owner','editor'),
                       (5,0,'保留现有业务','原业务描述',0,2,'owner','editor');
                INSERT INTO dh_asset_data_asset VALUES (42);
                INSERT INTO dh_asset_market_listing
                    (asset_id,market_category_id,display_name,description,frequency,delivery_types,revision,creator,updater)
                VALUES (42,9001,'现有资产','保留现有资产说明','每日','1,3',19,'owner','editor');
                """);
        String oldCategories = categorySnapshot("WHERE id IN (5,9000,9001)");
        String listings = listingSnapshot();

        migrate();

        assertEquals(oldCategories, categorySnapshot("WHERE id IN (5,9000,9001)"));
        assertEquals(listings, listingSnapshot());
        Map<String, ActualCategory> actual = liveCategories();
        assertTrue(actual.keySet().containsAll(expectedCategories().keySet()));
        assertEquals(9000, actual.get("综合决策").id());
        assertEquals(9001, actual.get("综合决策/综合态势").id());
        assertEquals(79, actual.size());
        assertEquals(9002, scalar("SELECT min(id) FROM " + CATEGORY + " WHERE creator='migration:0202'"));
        assertEquals("9077:true", sequenceSnapshot());
    }

    @Test
    void doesNotMergeDifferentParentsOrReuseLogicallyDeletedHistory() throws Exception {
        execute("""
                INSERT INTO dh_asset_market_category (id,parent_id,name,description,deleted)
                VALUES (100,0,'其他业务','保留其他业务',0),
                       (101,100,'综合态势','别的父级下的同名类目',0),
                       (800,0,'综合决策','已经删除的同名根类',1),
                       (801,800,'综合态势','已经删除的同名子类',1);
                """);
        String history = categorySnapshot("WHERE id IN (100,101,800,801)");

        migrate();

        assertEquals(history, categorySnapshot("WHERE id IN (100,101,800,801)"));
        Map<String, ActualCategory> actual = liveCategories();
        assertTrue(actual.keySet().containsAll(expectedCategories().keySet()));
        assertEquals(80, actual.size());
        assertEquals(101, actual.get("其他业务/综合态势").id());
        assertNotEquals(101, actual.get("综合决策/综合态势").id());
        assertNotEquals(800, actual.get("综合决策").id());
        assertNotEquals(801, actual.get("综合决策/综合态势").id());
        // 模板本身也包含跨父级同名叶子，必须保留为独立类目。
        assertNotEquals(actual.get("综合决策/综合评价").id(), actual.get("灌区/综合评价").id());
    }

    @Test
    void replayPreservesManualMaintenanceFullRowsAndSequenceState() throws Exception {
        migrate();
        execute("""
                UPDATE dh_asset_market_category SET description='登记后的人工维护', status=1,
                    sort=177, updater='manual-editor', update_time=TIMESTAMP '2026-01-01 01:00:00'
                WHERE name='综合决策' AND parent_id=0;
                """);
        String categories = categorySnapshot("");
        String sequence = sequenceSnapshot();

        migrate();

        assertEquals(categories, categorySnapshot(""));
        assertEquals(sequence, sequenceSnapshot());
        assertEquals(78, scalar("SELECT count(*) FROM " + CATEGORY));
    }

    @Test
    void replacesDeletedChildUnderReusedLiveParentAndRespectsCalledSequence() throws Exception {
        execute("""
                INSERT INTO dh_asset_market_category (id,parent_id,name,description,deleted)
                VALUES (10,0,'综合决策','人工根类',0),
                       (11,10,'综合态势','历史同路径子类',1);
                SELECT nextval('dh_asset_market_category_seq');
                """);
        String history = categorySnapshot("WHERE id IN (10,11)");

        migrate();

        assertEquals(history, categorySnapshot("WHERE id IN (10,11)"));
        Map<String, ActualCategory> actual = liveCategories();
        assertEquals(expectedCategories().keySet(), actual.keySet());
        assertEquals(10, actual.get("综合决策").id());
        assertNotEquals(11, actual.get("综合决策/综合态势").id());
        assertEquals(1001, scalar("SELECT min(id) FROM " + CATEGORY + " WHERE creator='migration:0202'"));
        assertEquals("1077:true", sequenceSnapshot());
    }

    @Test
    void insertionFailureRollsBackNewRowsAndSequenceRestart() throws Exception {
        execute("""
                INSERT INTO dh_asset_market_category (id,name,description) VALUES (3,'原有类目','原有内容');
                INSERT INTO dh_asset_data_asset VALUES (7);
                INSERT INTO dh_asset_market_listing (asset_id,market_category_id,display_name,frequency,delivery_types)
                VALUES (7,3,'原有挂载','每月','2');
                CREATE FUNCTION fail_water_market_insert() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.name='水土保持' THEN RAISE EXCEPTION '模拟插入中途失败'; END IF;
                    RETURN NEW;
                END;
                $$;
                CREATE TRIGGER reject_water_market_insert BEFORE INSERT ON dh_asset_market_category
                FOR EACH ROW EXECUTE FUNCTION fail_water_market_insert();
                """);
        String categories = categorySnapshot("");
        String listings = listingSnapshot();
        String sequence = sequenceSnapshot();

        SQLException error = assertThrows(SQLException.class, this::migrate);
        assertTrue(error.getMessage().contains("模拟插入中途失败"));
        execute("ROLLBACK");

        assertEquals(categories, categorySnapshot(""));
        assertEquals(listings, listingSnapshot());
        assertEquals(sequence, sequenceSnapshot());
        execute("DROP TRIGGER reject_water_market_insert ON dh_asset_market_category");
        migrate();
        assertEquals(79, scalar("SELECT count(*) FROM " + CATEGORY));
        assertEquals(1000, scalar("SELECT min(id) FROM " + CATEGORY + " WHERE creator='migration:0202'"));
    }

    @Test
    void rejectsNonUnitSequenceIncrementWithoutChanges() throws Exception {
        execute("ALTER SEQUENCE " + SEQUENCE + " INCREMENT BY 2");
        String sequence = sequenceSnapshot();

        SQLException error = assertThrows(SQLException.class, this::migrate);
        assertTrue(error.getMessage().contains("步长一"));
        execute("ROLLBACK");

        assertEquals(0, scalar("SELECT count(*) FROM " + CATEGORY));
        assertEquals(sequence, sequenceSnapshot());
        assertEquals(2, scalar("SELECT seqincrement FROM pg_sequence WHERE seqrelid='" + SEQUENCE + "'::regclass"));
    }

    @Test
    void rejectsSequenceOverflowBeforeInsertingAnyCategory() throws Exception {
        execute("ALTER SEQUENCE " + SEQUENCE + " RESTART WITH 9223372036854775800");
        String sequence = sequenceSnapshot();

        SQLException error = assertThrows(SQLException.class, this::migrate);
        assertTrue(error.getMessage().contains("剩余空间不足"));
        execute("ROLLBACK");

        assertEquals(0, scalar("SELECT count(*) FROM " + CATEGORY));
        assertEquals(sequence, sequenceSnapshot());
    }

    private Map<String, ExpectedCategory> expectedCategories() throws Exception {
        // 预期数据来自独立的参考站规范化采集文件，夹具不读取或解析待测迁移生成预期。
        try (var reader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(
                getClass().getResourceAsStream("/market/water-market-categories.tsv")), StandardCharsets.UTF_8))) {
            Map<String, ExpectedCategory> result = new LinkedHashMap<>();
            for (String line : reader.lines().toList()) {
                String[] fields = line.split("\t", -1);
                assertEquals(4, fields.length);
                String path = fields[0].isEmpty() ? fields[1] : fields[0] + "/" + fields[1];
                assertNull(result.put(path, new ExpectedCategory(fields[2], Integer.parseInt(fields[3]))));
            }
            return result;
        }
    }

    private Map<String, ActualCategory> liveCategories() throws SQLException {
        Map<String, ActualCategory> result = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("""
                SELECT category.id,category.parent_id,category.name,category.description,category.status,
                       category.sort,parent.name AS parent_name
                FROM dh_asset_market_category category
                LEFT JOIN dh_asset_market_category parent ON parent.id=category.parent_id
                WHERE category.deleted=0 ORDER BY category.id
                """)) {
            while (rows.next()) {
                String path = rows.getLong("parent_id") == 0 ? rows.getString("name")
                        : rows.getString("parent_name") + "/" + rows.getString("name");
                assertNull(result.put(path, new ActualCategory(rows.getLong("id"), rows.getString("description"),
                        rows.getInt("status"), rows.getInt("sort"))), path);
            }
        }
        return result;
    }

    private void migrate() throws Exception {
        execute(Files.readString(migrations().resolve(MIGRATION)));
    }

    private Path migrations() {
        for (Path directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
            Path migrations = directory.resolve("sql/postgresql/migrations");
            if (Files.isDirectory(migrations)) return migrations;
        }
        throw new IllegalStateException("无法定位仓库迁移目录");
    }

    private String categorySnapshot(String condition) throws SQLException {
        return string("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY id),'[]'::jsonb)::text FROM (SELECT * FROM "
                + CATEGORY + " " + condition + ") t");
    }

    private String listingSnapshot() throws SQLException {
        return string("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY asset_id),'[]'::jsonb)::text FROM dh_asset_market_listing t");
    }

    private String sequenceSnapshot() throws SQLException {
        return string("SELECT last_value::text || ':' || is_called::text FROM " + SEQUENCE);
    }

    private String string(String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private long scalar(String sql) throws SQLException {
        return Long.parseLong(string(sql));
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private record ExpectedCategory(String description, int sort) { }

    private record ActualCategory(long id, String description, int status, int sort) { }
}
