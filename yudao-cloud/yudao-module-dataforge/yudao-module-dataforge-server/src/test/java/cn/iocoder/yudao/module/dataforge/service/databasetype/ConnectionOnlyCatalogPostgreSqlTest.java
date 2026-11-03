package cn.iocoder.yudao.module.dataforge.service.databasetype;

import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.databasetype.DatabaseTypeDO;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 只操作显式指定的本机任务集群与随机数据库，不读取项目运行配置。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(120)
class ConnectionOnlyCatalogPostgreSqlTest {
    private static final String MIGRATION = "sql/postgresql/migrations/0197_dataforge_connect-only-connectors.sql";
    private static final String BASELINE = "sql/postgresql/datahub-center.sql";
    private static final Set<String> CODES = Set.of("GBASE8C", "GAUSSDB", "PHOENIX", "SAPHANA", "PRESTO", "ODPS",
            "OSS", "MINIO", "REDIS", "KAFKA", "RABBITMQ", "ROCKETMQ", "NEO4J", "MILVUS", "INFLUXDB2", "SFTP");
    private String controlUrl;
    private Path root;
    private String database;
    private Connection connection;

    @BeforeAll void findDedicatedCluster() {
        controlUrl = System.getenv("DATABASE_TYPE_CATALOG_TEST_JDBC_URL");
        assumeTrue(controlUrl != null && !controlUrl.isBlank(), "需要任务专用本机 PostgreSQL 控制库");
        assertTrue(controlUrl.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/postgres"));
        root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve(BASELINE))) root = root.getParent();
        assertNotNull(root);
    }
    @BeforeEach void createOwnedDatabase() throws Exception {
        database = "connect_0197_" + UUID.randomUUID().toString().replace("-", "");
        try (var control = DriverManager.getConnection(controlUrl, "catalog_test", ""); var sql = control.createStatement()) {
            sql.execute("CREATE DATABASE " + database);
        }
        connection = DriverManager.getConnection(controlUrl.substring(0, controlUrl.lastIndexOf('/') + 1) + database, "catalog_test", "");
    }
    @AfterEach void dropOwnedDatabase() throws Exception {
        if (connection != null) connection.close();
        if (database != null) {
            try (var control = DriverManager.getConnection(controlUrl, "catalog_test", ""); var sql = control.createStatement()) {
                sql.execute("DROP DATABASE " + database + " WITH (FORCE)");
            }
        }
    }

    @Test void freshBaselineExposesAllConnectorsWithoutInventingOtherCapabilities() throws Exception {
        try (var sql = connection.createStatement(); var runtime = DbSwitchRuntime.standalone()) {
            sql.execute(Files.readString(root.resolve(BASELINE)));
            sql.execute("SET search_path TO \"datahub-center\",public");
            verifyCatalog(sql);
            var service = new DatabaseTypeCapabilityService(); ReflectionTestUtils.setField(service, "runtime", runtime);
            for (String code : CODES) {
                var response = service.describe(DatabaseTypeDO.builder().typeCode(code).build());
                assertEquals("DECLARED", response.getCapabilities().get("CONNECT").status(), code);
                response.getCapabilities().forEach((operation, capability) -> {
                    if (!operation.equals("CONNECT")) assertEquals("UNSUPPORTED", capability.status(), code + ":" + operation);
                });
            }
            assertEquals("1", one(sql, "SELECT count(*) FROM dh_dataforge_database_type WHERE type_code='INFLUXDB3' AND deleted=0"));
        }
    }

    @Test void upgradeAndReplayPreserveIdsReferencesCustomTemplatesAndDescriptions() throws Exception {
        try (var sql = connection.createStatement()) {
            sql.execute("""
                    CREATE SCHEMA "datahub-center";
                    SET search_path TO "datahub-center",public;
                    CREATE SEQUENCE dh_foundation_database_type_seq START 400;
                    CREATE SEQUENCE system_dict_data_seq START 500;
                    CREATE TABLE dh_dataforge_database_type(id bigint PRIMARY KEY,type_code varchar(255),type_name varchar(255),
                      description varchar(512),category_code varchar(255),protocol_type varchar(255),icon_path varchar(255),
                      url_templates text,port_templates varchar(20),validation_query text,sort integer,status smallint,
                      creator varchar(64),updater varchar(64),deleted smallint DEFAULT 0,update_time timestamp DEFAULT CURRENT_TIMESTAMP);
                    CREATE TABLE dh_dataforge_database(id bigint PRIMARY KEY,database_type integer,database_ip varchar(50),
                      database_port varchar(10),user_name varchar(50),database_name varchar(200),extend_config text);
                    CREATE TABLE system_dict_data(id bigint PRIMARY KEY,sort integer,label varchar(255),value varchar(255),
                      dict_type varchar(255),status smallint,creator varchar(64),updater varchar(64),deleted smallint DEFAULT 0);
                    INSERT INTO dh_dataforge_database_type(id,type_code,type_name,description,category_code,protocol_type,
                      icon_path,url_templates,port_templates,sort,status) VALUES
                      (900,'sftp','保留的名称','保留的说明','FILE_OBJECT_STORAGE','SFTP','/custom.svg','custom-template','2222',1,1);
                    INSERT INTO dh_dataforge_database VALUES(1,900,'fixture.example.invalid','2222','fixture','fixture',NULL);
                    """);
            String migration = Files.readString(root.resolve(MIGRATION)); sql.execute(migration);
            sql.execute("SET search_path TO \"datahub-center\",public");
            String snapshot = one(sql, "SELECT jsonb_agg(to_jsonb(t) ORDER BY id)::text FROM dh_dataforge_database_type t");
            String dictionary = one(sql, "SELECT jsonb_agg(to_jsonb(t) ORDER BY id)::text FROM system_dict_data t");
            sql.execute(migration); sql.execute("SET search_path TO \"datahub-center\",public");
            assertEquals(snapshot, one(sql, "SELECT jsonb_agg(to_jsonb(t) ORDER BY id)::text FROM dh_dataforge_database_type t"));
            assertEquals(dictionary, one(sql, "SELECT jsonb_agg(to_jsonb(t) ORDER BY id)::text FROM system_dict_data t"));
            assertEquals("保留的说明", one(sql, "SELECT description FROM dh_dataforge_database_type WHERE id=900"));
            assertEquals("custom-template", one(sql, "SELECT url_templates FROM dh_dataforge_database_type WHERE id=900"));
            assertEquals("1", one(sql, "SELECT status::text FROM dh_dataforge_database_type WHERE id=900"));
            assertEquals("900", one(sql, "SELECT database_type::text FROM dh_dataforge_database WHERE id=1"));
            assertEquals("16", one(sql, "SELECT count(*) FROM dh_dataforge_database_type WHERE deleted=0"));
            assertEquals("253", one(sql, "SELECT character_maximum_length::text FROM information_schema.columns WHERE table_schema='datahub-center' AND table_name='dh_dataforge_database' AND column_name='database_ip'"));
            assertEquals("512", one(sql, "SELECT character_maximum_length::text FROM information_schema.columns WHERE table_schema='datahub-center' AND table_name='dh_dataforge_database' AND column_name='user_name'"));
        }
    }

    private static void verifyCatalog(Statement sql) throws Exception {
        var actual = new java.util.HashSet<String>();
        try (var rows = sql.executeQuery("SELECT type_code FROM dh_dataforge_database_type WHERE deleted=0 AND status=0")) {
            while (rows.next()) if (CODES.contains(rows.getString(1))) actual.add(rows.getString(1));
        }
        assertEquals(CODES, actual);
        assertEquals("46", one(sql, "SELECT count(*) FROM dh_dataforge_database_type WHERE deleted=0 AND status=0"));
        assertEquals("1", one(sql, "SELECT count(*) FROM system_dict_data WHERE dict_type='protocol_type' AND value='SFTP' AND deleted=0"));
    }
    private static String one(Statement sql, String query) throws Exception {
        try (var rows = sql.executeQuery(query)) { assertTrue(rows.next()); return rows.getString(1); }
    }
}
