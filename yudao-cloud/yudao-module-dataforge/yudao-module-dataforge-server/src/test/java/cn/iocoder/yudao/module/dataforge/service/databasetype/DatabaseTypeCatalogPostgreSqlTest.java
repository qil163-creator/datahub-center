package cn.iocoder.yudao.module.dataforge.service.databasetype;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.databasetype.DatabaseTypeDO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 使用任务专用本机 PostgreSQL 验证目录的新库、升级和重放契约，不连接现有项目数据库。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(120)
class DatabaseTypeCatalogPostgreSqlTest {

    private static final String MIGRATION = "sql/postgresql/migrations/0173_dataforge_connection-type-catalog.sql";
    private static final String ICON_MIGRATION = "sql/postgresql/migrations/0209_dataforge_connection-icons-png.sql";
    private static final String REMOVE_DRIVER_PROFILE_MIGRATION = "sql/postgresql/migrations/0175_dataforge_remove-connection-driver-profile.sql";
    private static final String BASELINE = "sql/postgresql/datahub-center.sql";
    private static final String SID_TEMPLATE = "jdbc:oracle:thin:@${host}:${port}:${sid}";
    private static final String SERVICE_TEMPLATE = "jdbc:oracle:thin:@//${host}:${port}/${serviceName}";
    private String controlUrl;
    private String database;
    private Path root;
    private Connection connection;

    @BeforeAll
    void findDedicatedPostgresAndRepository() {
        controlUrl = System.getenv("DATABASE_TYPE_CATALOG_TEST_JDBC_URL");
        assumeTrue(controlUrl != null && !controlUrl.isBlank(), "需要显式提供任务专用本机 PostgreSQL 控制库");
        assertTrue(controlUrl.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/postgres"),
                "只接受本机隔离集群的控制库");
        root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve(BASELINE))) root = root.getParent();
        assertNotNull(root, "缺少仓库基线");
    }

    @BeforeEach
    void createDisposableDatabase() throws Exception {
        database = "catalog_0175_" + UUID.randomUUID().toString().replace("-", "");
        try (var control = DriverManager.getConnection(controlUrl, "catalog_test", "");
             var sql = control.createStatement()) {
            sql.execute("CREATE DATABASE " + database);
        }
        String url = controlUrl.substring(0, controlUrl.lastIndexOf('/') + 1) + database;
        connection = DriverManager.getConnection(url, "catalog_test", "");
    }

    @AfterEach
    void dropOnlyTheOwnedDatabase() throws Exception {
        if (connection != null) connection.close();
        if (database != null) {
            try (var control = DriverManager.getConnection(controlUrl, "catalog_test", "");
                 var sql = control.createStatement()) {
                sql.execute("DROP DATABASE " + database + " WITH (FORCE)");
            }
        }
    }

    @Test
    void freshBaselineExposesEveryRegisteredProductAndKeepsBothOracleTemplates() throws Exception {
        try (var sql = connection.createStatement()) {
            sql.execute(Files.readString(root.resolve(BASELINE)));
            sql.execute("SET search_path TO \"datahub-center\", public");
            verifyCompleteCatalog(sql);
            verifyOracleTemplates(sql);
            verifyRetiredTypeAndRemovedDriverColumn(sql);
            verifyCategoriesAndDescriptions(sql);
            verifyConnectionIcons(sql, baselineIconPaths());
            verifyRemovedConnectionDriverProfile(sql);
            assertSequenceIsPastCatalogIds(sql);
        }
    }

    @Test
    void removingConnectionDriverSelectionPreservesOtherFieldsAndCanReplay() throws Exception {
        try (var sql = connection.createStatement()) {
            sql.execute("""
                    CREATE SCHEMA "datahub-center";
                    SET search_path TO "datahub-center", public;
                    CREATE TABLE dh_dataforge_database (
                      id bigint PRIMARY KEY, name varchar(200) NOT NULL,
                      database_type integer NOT NULL, database_schema varchar(200),
                      jdbc_url varchar(1000), usage_type smallint, connection_status smallint,
                      update_time timestamp NOT NULL,
                      driver_profile_id varchar(64) CONSTRAINT ck_dataforge_database_driver_profile
                        CHECK (driver_profile_id IS NULL OR driver_profile_id ~ '^[a-z][a-z0-9-]{0,63}$')
                    );
                    INSERT INTO dh_dataforge_database VALUES
                      (101, '迁移验证连接', 2, 'public', 'jdbc:postgresql://db.example.invalid:5432/fixture',
                       1, 2, '2026-10-15 10:00:00', 'fixture-profile'),
                      (102, '使用默认驱动的连接', 1, NULL, 'jdbc:mysql://db.example.invalid:3306/fixture',
                       2, 0, '2026-10-15 10:00:00', NULL);
                    COMMENT ON COLUMN dh_dataforge_database.name IS '连接名称';
                    """);
            String snapshot = one(sql, "SELECT jsonb_agg(to_jsonb(d) - 'driver_profile_id' ORDER BY id)::text "
                    + "FROM dh_dataforge_database d");
            String migration = Files.readString(root.resolve(REMOVE_DRIVER_PROFILE_MIGRATION));
            for (int replay = 0; replay < 2; replay++) {
                sql.execute(migration);
                sql.execute("SET search_path TO \"datahub-center\", public");
                verifyRemovedConnectionDriverProfile(sql);
                assertEquals(snapshot, one(sql, "SELECT jsonb_agg(to_jsonb(d) ORDER BY id)::text "
                        + "FROM dh_dataforge_database d"), "首次执行及重放均不得改变其他连接字段和编号");
                assertEquals("连接名称", one(sql,
                        "SELECT col_description('\"datahub-center\".dh_dataforge_database'::regclass,attnum) "
                                + "FROM pg_attribute WHERE attrelid='\"datahub-center\".dh_dataforge_database'::regclass "
                                + "AND attname='name'"));
            }
        }
    }

    private static void verifyRemovedConnectionDriverProfile(Statement sql) throws Exception {
        assertEquals("0", one(sql, "SELECT count(*) FROM information_schema.columns "
                + "WHERE table_schema='datahub-center' AND table_name='dh_dataforge_database' "
                + "AND column_name='driver_profile_id'"));
        assertEquals("0", one(sql, "SELECT count(*) FROM pg_constraint "
                + "WHERE conrelid='\"datahub-center\".dh_dataforge_database'::regclass "
                + "AND conname='ck_dataforge_database_driver_profile'"));
    }

    @Test
    void upgradePreservesReferencesAndCustomIdsAndReplaysWithoutDuplicates() throws Exception {
        try (var sql = connection.createStatement()) {
            createLegacyCatalog(sql);
            assertEquals("20", one(sql, "SELECT count(*) FROM dh_dataforge_database_type"));
            // 模拟已被用户占用的新库默认编号，增量不得覆盖这些目录记录。
            sql.execute("INSERT INTO dh_dataforge_database_type(id,type_code,type_name,status) "
                    + "VALUES (21,'custom-unavailable','用户自定义停用类型',1)");
            // 已有 SQLite 别名目录须补分类和描述，不得复制产品或覆盖用户文件路径。
            sql.execute("INSERT INTO dh_dataforge_database_type(id,type_code,type_name,category_code,protocol_type,url_templates) "
                    + "VALUES (22,'SQLite','已有 SQLite','DOMESTIC','JDBC','jdbc:sqlite:existing.db')");
            String migration = Files.readString(root.resolve(MIGRATION));
            sql.execute(migration);
            sql.execute("SET search_path TO \"datahub-center\", public");
            verifyCompleteCatalog(sql);
            verifyOracleTemplates(sql);
            verifyRetiredTypeAndRemovedDriverColumn(sql);
            verifyCategoriesAndDescriptions(sql);
            assertEquals("3,4,18", one(sql, "SELECT string_agg(database_type::text,',' ORDER BY id) "
                    + "FROM dh_dataforge_database"));
            assertEquals("3", one(sql, "SELECT count(*) FROM dh_dataforge_database d "
                    + "JOIN dh_dataforge_database_type t ON t.id=d.database_type"));
            assertEquals("用户自定义停用类型", one(sql,
                    "SELECT type_name FROM dh_dataforge_database_type WHERE id=21"));
            assertEquals("1", one(sql, "SELECT count(*) FROM dh_dataforge_database_type WHERE "
                    + "regexp_replace(upper(type_code),'[-_[:space:]]','','g') IN ('SQLITE','SQLITE3') "
                    + "AND status=0 AND deleted=0"));
            assertEquals("SQLite", one(sql, "SELECT type_code FROM dh_dataforge_database_type WHERE id=22"));
            assertEquals("jdbc:sqlite:existing.db", one(sql,
                    "SELECT url_templates FROM dh_dataforge_database_type WHERE id=22"));
            assertEquals("RELATIONAL", one(sql,
                    "SELECT category_code FROM dh_dataforge_database_type WHERE id=22"));
            assertFalse(one(sql, "SELECT description FROM dh_dataforge_database_type WHERE id=22").isBlank());
            sql.execute("UPDATE dh_dataforge_database_type SET description='用户维护的连接描述' WHERE id=1");
            String beforeReplay = catalogSnapshot(sql);
            String dictionaryBeforeReplay = dictionarySnapshot(sql);
            sql.execute(migration);
            sql.execute("SET search_path TO \"datahub-center\", public");
            assertEquals(beforeReplay, catalogSnapshot(sql), "重放不得新增目录、改变编号或覆盖已有配置");
            assertEquals(dictionaryBeforeReplay, dictionarySnapshot(sql), "重放不得重复新增分类字典");
            assertEquals("用户维护的连接描述", one(sql,
                    "SELECT description FROM dh_dataforge_database_type WHERE id=1"));
            verifyCompleteCatalog(sql);
            assertSequenceIsPastCatalogIds(sql);

            // 用户在已有产品上维护的自定义图标必须在首次升级时保留，包括 SQLite 别名。
            String customSqliteIcon = "https://icons.example/custom-sqlite.svg";
            sql.execute("UPDATE dh_dataforge_database_type SET icon_path='" + customSqliteIcon + "' WHERE id=22");
            sql.execute("UPDATE dh_dataforge_database_type SET icon_path='   ' WHERE type_code='TIDB'");
            String iconMigration = Files.readString(root.resolve(ICON_MIGRATION));
            sql.execute(iconMigration);
            sql.execute("SET search_path TO \"datahub-center\", public");
            var upgradedIcons = upgradedIconPaths();
            upgradedIcons.remove("SQLITE3"); // SQLite 由带自定义图标的别名行承载，迁移不得覆盖
            verifyConnectionIcons(sql, upgradedIcons);
            assertEquals(customSqliteIcon, one(sql,
                    "SELECT icon_path FROM dh_dataforge_database_type WHERE id=22"), "用户自定义图标不得被 PNG 迁移覆盖");
            verifyCompleteCatalog(sql);

            // 补齐后再改为自定义图标，重放也不得覆盖或复制目录。
            String customTidbIcon = "https://icons.example/custom-tidb.svg";
            sql.execute("UPDATE dh_dataforge_database_type SET icon_path='" + customTidbIcon + "' WHERE type_code='TIDB'");
            String beforeIconReplay = catalogSnapshot(sql);
            String dictionaryBeforeIconReplay = dictionarySnapshot(sql);
            sql.execute(iconMigration);
            sql.execute("SET search_path TO \"datahub-center\", public");
            assertEquals(beforeIconReplay, catalogSnapshot(sql), "图标重放不得新增目录、改变编号或覆盖已有配置");
            assertEquals(dictionaryBeforeIconReplay, dictionarySnapshot(sql), "图标迁移不得修改分类字典");
            var replayIcons = upgradedIconPaths();
            replayIcons.remove("SQLITE3");
            replayIcons.remove("TIDB"); // TIDB 已改为自定义图标，重放不得回写内置 PNG
            verifyConnectionIcons(sql, replayIcons);
            assertEquals(customSqliteIcon, one(sql,
                    "SELECT icon_path FROM dh_dataforge_database_type WHERE id=22"), "重放不得覆盖 SQLite 别名自定义图标");
            assertEquals(customTidbIcon, one(sql,
                    "SELECT icon_path FROM dh_dataforge_database_type WHERE type_code='TIDB'"), "重放不得覆盖用户自定义图标");
            verifyCompleteCatalog(sql);
        }
    }

    private static void verifyConnectionIcons(Statement sql, Map<String, String> expected) throws Exception {
        var actual = new HashMap<String, String>();
        try (var rows = sql.executeQuery("SELECT type_code, icon_path FROM dh_dataforge_database_type "
                + "WHERE deleted=0 AND icon_path LIKE '/connection-icons/%' ORDER BY id")) {
            while (rows.next()) {
                actual.put(rows.getString(1), rows.getString(2));
            }
        }
        assertEquals(expected, actual, "内置连接图标均须指向随管理端发布的本地 PNG 资源");
        assertEquals("0", one(sql, "SELECT count(*) FROM dh_dataforge_database_type "
                + "WHERE deleted=0 AND icon_path LIKE '/connection-icons/%.svg'"), "不得继续引用已替换的 SVG 图标");
        assertEquals("连接图标路径", one(sql,
                "SELECT col_description('\"datahub-center\".dh_dataforge_database_type'::regclass,attnum) "
                + "FROM pg_attribute WHERE attrelid='\"datahub-center\".dh_dataforge_database_type'::regclass "
                + "AND attname='icon_path'"));
        assertEquals("连接名称", one(sql,
                "SELECT col_description('\"datahub-center\".dh_dataforge_database'::regclass,attnum) "
                + "FROM pg_attribute WHERE attrelid='\"datahub-center\".dh_dataforge_database'::regclass "
                + "AND attname='name'"));
    }

    /** 0209 图标迁移在 0173 历史目录上的期望结果：九种历史新增产品的 PNG 图标。 */
    private static Map<String, String> upgradedIconPaths() {
        var icons = new HashMap<String, String>();
        icons.put("SQLSERVER2000", "/connection-icons/sqlserver2000.png");
        icons.put("DB2", "/connection-icons/db2.png");
        icons.put("SQLITE3", "/connection-icons/sqlite3.png");
        icons.put("SYBASE", "/connection-icons/sybase.png");
        icons.put("MONGODB", "/connection-icons/mongodb.png");
        icons.put("TIDB", "/connection-icons/tidb.png");
        icons.put("TDENGINE", "/connection-icons/tdengine.png");
        icons.put("INFLUXDB3", "/connection-icons/influxdb3.png");
        icons.put("ELASTICSEARCH", "/connection-icons/elasticsearch.png");
        return icons;
    }

    /** 当前基线（含 0209 图标迁移）中全部本地 PNG 连接图标。 */
    private static Map<String, String> baselineIconPaths() {
        var icons = upgradedIconPaths();
        icons.put("FTP", "/connection-icons/ftp.png");
        icons.put("HDFS", "/connection-icons/hdfs.png");
        icons.put("GBASE8C", "/connection-icons/gbase8c.png");
        icons.put("GAUSSDB", "/connection-icons/gaussdb.png");
        icons.put("PHOENIX", "/connection-icons/phoenix.png");
        icons.put("SAPHANA", "/connection-icons/saphana.png");
        icons.put("PRESTO", "/connection-icons/presto.png");
        icons.put("ODPS", "/connection-icons/odps.png");
        icons.put("OSS", "/connection-icons/oss.png");
        icons.put("MINIO", "/connection-icons/minio.png");
        icons.put("REDIS", "/connection-icons/redis.png");
        icons.put("KAFKA", "/connection-icons/kafka.png");
        icons.put("RABBITMQ", "/connection-icons/rabbitmq.png");
        icons.put("ROCKETMQ", "/connection-icons/rocketmq.png");
        icons.put("NEO4J", "/connection-icons/neo4j.png");
        icons.put("MILVUS", "/connection-icons/milvus.png");
        icons.put("INFLUXDB2", "/connection-icons/influxdb2.png");
        icons.put("SFTP", "/connection-icons/sftp.png");
        return icons;
    }

    private static void verifyCompleteCatalog(Statement sql) throws Exception {
        try (var runtime = DbSwitchRuntime.standalone()) {
            var expected = runtime.products().products().stream().map(descriptor -> descriptor.product())
                    .filter(product -> product.getIndex() <= 27)
                    .collect(Collectors.toCollection(() -> EnumSet.noneOf(ProductTypeEnum.class)));
            assertEquals(27, expected.size(), "0173 历史迁移覆盖的产品数量保持不变");
            var actual = EnumSet.noneOf(ProductTypeEnum.class);
            var service = new DatabaseTypeCapabilityService();
            ReflectionTestUtils.setField(service, "runtime", runtime);
            try (var rows = sql.executeQuery("SELECT id,type_code FROM dh_dataforge_database_type "
                    + "WHERE status=0 AND deleted=0 ORDER BY id")) {
                int templates = 0;
                while (rows.next()) {
                    String code = rows.getString("type_code");
                    var product = runtime.products().resolve(code).orElse(null);
                    if (product == null) {
                        assertTrue(Set.of("FTP", "HDFS", "OSS", "MINIO", "SFTP", "REDIS", "KAFKA", "RABBITMQ", "ROCKETMQ")
                                .contains(code.toUpperCase(java.util.Locale.ROOT)), "非数据库连接应有独立协议归属：" + code);
                        continue;
                    }
                    if (product.getIndex() > 27) continue;
                    actual.add(product);
                    var type = DatabaseTypeDO.builder().id(rows.getLong("id")).typeCode(code).build();
                    var response = service.describe(type);
                    assertEquals(product.name(), response.getProductId());
                    assertFalse(response.getCapabilities().isEmpty(), code);
                    templates++;
                }
                assertEquals(28, templates, "27 种产品与 Oracle 的第二种 URL 模板");
            }
            assertEquals(expected, actual, "有效连接目录必须覆盖全部注册产品");
        }
    }

    private static void verifyOracleTemplates(Statement sql) throws Exception {
        assertEquals("ORACLE", one(sql, "SELECT type_code FROM dh_dataforge_database_type WHERE id=3"));
        assertEquals("ORACLE", one(sql, "SELECT type_code FROM dh_dataforge_database_type WHERE id=4"));
        assertEquals(SID_TEMPLATE, one(sql, "SELECT url_templates FROM dh_dataforge_database_type WHERE id=3"));
        assertEquals(SERVICE_TEMPLATE, one(sql, "SELECT url_templates FROM dh_dataforge_database_type WHERE id=4"));
        assertEquals("1521", one(sql, "SELECT port_templates FROM dh_dataforge_database_type WHERE id=3"));
    }

    private static void verifyRetiredTypeAndRemovedDriverColumn(Statement sql) throws Exception {
        assertEquals("0", one(sql, "SELECT count(*) FROM dh_dataforge_database_type WHERE status=0 AND deleted=0 "
                + "AND regexp_replace(upper(type_code),'[-_[:space:]]','','g')='GBASE8S'"));
        assertEquals("1", one(sql, "SELECT count(*) FROM dh_dataforge_database_type WHERE id=18 AND status=1 AND deleted=1"));
        assertFalse(one(sql, "SELECT description FROM dh_dataforge_database_type WHERE id=18").isBlank(),
                "退役目录仍须保留可读的连接描述");
        assertEquals("0", one(sql, "SELECT count(*) FROM information_schema.columns WHERE table_schema='datahub-center' "
                + "AND table_name='dh_dataforge_database_type' AND column_name='driver_class'"));
    }

    private static void assertSequenceIsPastCatalogIds(Statement sql) throws Exception {
        long maximum = Long.parseLong(one(sql, "SELECT max(id) FROM dh_dataforge_database_type"));
        long next = Long.parseLong(one(sql, "SELECT nextval('\"datahub-center\".dh_foundation_database_type_seq')"));
        assertTrue(next > maximum, "目录序列不得与已存在的编号冲突");
    }

    private static void verifyCategoriesAndDescriptions(Statement sql) throws Exception {
        var expected = Set.of("RELATIONAL", "ANALYTICAL", "FILE_OBJECT_STORAGE", "NOSQL_CACHE",
                "MESSAGE_QUEUE", "TIME_SERIES", "SEMI_STRUCTURED", "VECTOR");
        var actual = new java.util.HashSet<String>();
        try (var rows = sql.executeQuery("SELECT value FROM system_dict_data "
                + "WHERE dict_type='category_code' AND status=0 AND deleted=0")) {
            int count = 0;
            while (rows.next()) {
                actual.add(rows.getString(1));
                count++;
            }
            assertEquals(8, count, "默认分类字典只保留八个有效类别");
        }
        assertEquals(expected, actual);
        assertEquals("连接分类", one(sql, "SELECT name FROM system_dict_type "
                + "WHERE type='category_code' AND status=0 AND deleted=0"));
        assertEquals("0", one(sql, "SELECT count(*) FROM dh_dataforge_database_type WHERE status=0 AND deleted=0 "
                + "AND category_code IN ('DOMESTIC','DOCUMENT_CACHE_SEARCH')"));
        assertEquals("0", one(sql, "SELECT count(*) FROM dh_dataforge_database_type WHERE status=0 AND deleted=0 "
                + "AND (description IS NULL OR btrim(description)='')"));
        assertEquals("512", one(sql, "SELECT character_maximum_length::text FROM information_schema.columns "
                + "WHERE table_schema='datahub-center' AND table_name='dh_dataforge_database_type' AND column_name='description'"));
        assertTrue(one(sql, "SELECT col_description('\"datahub-center\".dh_dataforge_database_type'::regclass,attnum) "
                + "FROM pg_attribute WHERE attrelid='\"datahub-center\".dh_dataforge_database_type'::regclass "
                + "AND attname='category_code'").contains("连接分类"));
        assertEquals("连接描述", one(sql,
                "SELECT col_description('\"datahub-center\".dh_dataforge_database_type'::regclass,attnum) "
                + "FROM pg_attribute WHERE attrelid='\"datahub-center\".dh_dataforge_database_type'::regclass "
                + "AND attname='description'"));
        assertEquals("2", one(sql, "SELECT count(*) FROM dh_dataforge_database_type WHERE "
                + "type_code IN ('TDENGINE','INFLUXDB3') AND category_code='TIME_SERIES' AND status=0 AND deleted=0"));
        assertEquals("2", one(sql, "SELECT count(*) FROM dh_dataforge_database_type WHERE "
                + "type_code IN ('MONGODB','ELASTICSEARCH') AND category_code='NOSQL_CACHE' AND status=0 AND deleted=0"));
    }

    private static String catalogSnapshot(Statement sql) throws Exception {
        return one(sql, "SELECT jsonb_agg(to_jsonb(t)-'update_time' ORDER BY id)::text FROM dh_dataforge_database_type t");
    }

    private static String dictionarySnapshot(Statement sql) throws Exception {
        return one(sql, "SELECT jsonb_agg(to_jsonb(t)-'update_time' ORDER BY id)::text FROM system_dict_data t "
                + "WHERE dict_type='category_code'");
    }

    private static String one(Statement sql, String query) throws Exception {
        try (var rows = sql.executeQuery(query)) {
            assertTrue(rows.next(), query);
            return rows.getString(1);
        }
    }

    /** 0173 前的单租户目录：保留原 20 个编号、Oracle 两种模板与被引用的 GBase8s。 */
    private static void createLegacyCatalog(Statement sql) throws Exception {
        sql.execute("""
                CREATE SCHEMA "datahub-center";
                SET search_path TO "datahub-center", public;
                CREATE SEQUENCE dh_foundation_database_type_seq START 21;
                CREATE TABLE dh_dataforge_database_type (
                  id bigint PRIMARY KEY, type_code varchar(255), type_name varchar(255),
                  category_code varchar(255), protocol_type varchar(255), icon_path varchar(255),
                  url_templates varchar(550), port_templates varchar(255), driver_class varchar(255),
                  validation_query varchar(255), sort integer NOT NULL DEFAULT 0, status smallint NOT NULL DEFAULT 0,
                  remark varchar(512), creator varchar(64), create_time timestamp NOT NULL DEFAULT now(),
                  updater varchar(64), update_time timestamp NOT NULL DEFAULT now(), deleted smallint NOT NULL DEFAULT 0
                );
                INSERT INTO dh_dataforge_database_type(id,type_code,type_name,category_code,protocol_type,url_templates,port_templates)
                VALUES
                  (1,'MySQL','MySQL','RELATIONAL','JDBC','jdbc:mysql://${host}:${port}/${dbName}','3306'),
                  (2,'PostgreSQL','PostgreSQL','RELATIONAL','JDBC','jdbc:postgresql://${host}:${port}/${dbName}','5432'),
                  (3,'Oracle SID','Oracle SID','RELATIONAL','JDBC','jdbc:oracle:thin:@${host}:${port}:${sid}','1512'),
                  (4,'Oracle Service','Oracle Service','RELATIONAL','JDBC','jdbc:oracle:thin:@//${host}:${port}/${serviceName}','1521'),
                  (5,'SQL Server','SQL Server','RELATIONAL','JDBC','jdbc:sqlserver://${host}:${port};databaseName=${dbName}','1433'),
                  (6,'MariaDB','MariaDB','RELATIONAL','JDBC','jdbc:mariadb://${host}:${port}/${dbName}','3306'),
                  (7,'Doris','Doris','ANALYTICAL','JDBC','jdbc:mysql://${host}:${port}/${dbName}','9030'),
                  (8,'StarRocks','StarRocks','ANALYTICAL','JDBC','jdbc:starrocks://${host}:${port}/${dbName}','9030'),
                  (9,'ClickHouse','ClickHouse','ANALYTICAL','HTTP','jdbc:clickhouse:http://${host}:${port}/${dbName}','8123'),
                  (10,'Greenplum','Greenplum','ANALYTICAL','JDBC','jdbc:postgresql://${host}:${port}/${dbName}','5432'),
                  (11,'DM','DM','DOMESTIC','JDBC','jdbc:dm://${host}:${port}','5236'),
                  (12,'HighGo','瀚高','DOMESTIC','JDBC','jdbc:highgo://${host}:${port}/${dbName}','5866'),
                  (13,'KingbaseES','人大金仓','DOMESTIC','JDBC','jdbc:kingbase8://${host}:${port}/${dbName}','54321'),
                  (14,'OSCAR','神通','DOMESTIC','JDBC','jdbc:oscar://${host}:${port}/${dbName}','2003'),
                  (15,'openGauss','openGauss','DOMESTIC','JDBC','jdbc:opengauss://${host}:${port}/${dbName}','5432'),
                  (16,'UXDB','优炫','DOMESTIC','JDBC','jdbc:uxdb://${host}:${port}/${dbName}','5432'),
                  (17,'GBASE_8A','GBASE_8A','DOMESTIC','JDBC','jdbc:gbase://${host}:${port}/${dbName}','5258'),
                  (18,'GBASE_8S','GBASE_8S','DOMESTIC','JDBC','jdbc:gbasedbt-sqli://${host}:${port}/${dbName}:GBASEDBTSERVER=${serverName}','9088'),
                  (19,'OceanBase','OceanBase','ANALYTICAL','JDBC','jdbc:oceanbase://${host}:${port}/${dbName}','2881'),
                  (20,'Hive','Hive','ANALYTICAL','JDBC','jdbc:hive2://${host}:${port}/${dbName}','10000');
                CREATE TABLE dh_dataforge_database (
                  id bigint PRIMARY KEY, name varchar(255), usage_type smallint,
                  database_type integer NOT NULL REFERENCES dh_dataforge_database_type(id)
                );
                INSERT INTO dh_dataforge_database(id,database_type) VALUES (101,3),(102,4),(103,18);
                CREATE SEQUENCE system_dict_data_seq START 3543;
                CREATE SEQUENCE system_dict_type_seq START 2164;
                CREATE TABLE system_dict_data (
                  id bigint PRIMARY KEY DEFAULT nextval('system_dict_data_seq'), sort integer NOT NULL DEFAULT 0,
                  label varchar(100) NOT NULL DEFAULT '', value varchar(100) NOT NULL DEFAULT '',
                  dict_type varchar(100) NOT NULL DEFAULT '', status smallint NOT NULL DEFAULT 0,
                  color_type varchar(100) DEFAULT '', css_class varchar(100) DEFAULT '', remark varchar(500),
                  creator varchar(64) DEFAULT '', create_time timestamp NOT NULL DEFAULT now(),
                  updater varchar(64) DEFAULT '', update_time timestamp NOT NULL DEFAULT now(),
                  deleted smallint NOT NULL DEFAULT 0
                );
                CREATE TABLE system_dict_type (
                  id bigint PRIMARY KEY DEFAULT nextval('system_dict_type_seq'), name varchar(100) NOT NULL DEFAULT '',
                  type varchar(100) NOT NULL DEFAULT '', status smallint NOT NULL DEFAULT 0, remark varchar(500),
                  creator varchar(64) DEFAULT '', create_time timestamp NOT NULL DEFAULT now(),
                  updater varchar(64) DEFAULT '', update_time timestamp NOT NULL DEFAULT now(),
                  deleted smallint NOT NULL DEFAULT 0, deleted_time timestamp
                );
                INSERT INTO system_dict_type(id,name,type) VALUES (2157,'数据源分类','category_code');
                INSERT INTO system_dict_data(id,sort,label,value,dict_type) VALUES
                  (3522,1,'关系型数据库','RELATIONAL','category_code'),
                  (3523,3,'国产数据库','DOMESTIC','category_code'),
                  (3524,2,'数仓分析','ANALYTICAL','category_code'),
                  (3525,4,'文档／缓存／检索','DOCUMENT_CACHE_SEARCH','category_code');
                """);
    }
}
