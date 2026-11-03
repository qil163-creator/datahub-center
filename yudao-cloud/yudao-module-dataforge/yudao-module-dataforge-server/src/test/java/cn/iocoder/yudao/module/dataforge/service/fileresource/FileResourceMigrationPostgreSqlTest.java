package cn.iocoder.yudao.module.dataforge.service.fileresource;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.sql.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 用真实 PostgreSQL 隔离模式验证替换、审计约束与重复执行，不接触平台业务库。 */
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[^?]+")
class FileResourceMigrationPostgreSqlTest {
    private final String schema="file_resource_"+UUID.randomUUID().toString().replace("-","");
    private Connection connection;
    private String migration;
    @BeforeEach void setup() throws Exception {
        connection=DriverManager.getConnection(System.getProperty("datahub.test.postgres.url"),
                System.getProperty("datahub.test.postgres.user","file_resource_test"),"");
        execute("CREATE SCHEMA "+schema+"; SET search_path TO "+schema+",pg_catalog");
        Path root=Path.of("").toAbsolutePath();
        String relative="sql/postgresql/migrations/0186_dataforge_remote-file-management.sql";
        while(root!=null && !Files.exists(root.resolve(relative)))root=root.getParent();
        assertNotNull(root);
        migration=Files.readString(root.resolve(relative)).replace("\"datahub-center\"","\""+schema+"\"");
        execute("""
            CREATE SEQUENCE dh_foundation_database_type_seq;
            CREATE SEQUENCE system_menu_seq;
            CREATE SEQUENCE system_menu_id_seq;
            CREATE SEQUENCE dh_dataforge_file_seq;
            CREATE SEQUENCE dh_dataforge_file_category_seq;
            CREATE TABLE dh_dataforge_file(id bigint PRIMARY KEY);
            CREATE TABLE dh_dataforge_file_category(id bigint PRIMARY KEY);
            INSERT INTO dh_dataforge_file VALUES(1);
            CREATE TABLE dh_dataforge_database_type(id bigint PRIMARY KEY,type_code text,type_name text,description text,
                category_code text,protocol_type text,port_templates text,sort integer,status smallint,creator text,updater text,deleted smallint DEFAULT 0);
            CREATE TABLE system_menu(id bigint PRIMARY KEY,name text,permission text,type integer,sort integer,parent_id bigint,
                path text,component text,status integer,visible boolean,keep_alive boolean,always_show boolean,creator text,
                updater text,update_time timestamp,deleted smallint DEFAULT 0);
            INSERT INTO system_menu(id,name,permission,type,parent_id,component) VALUES
                (6420,'文件采集','dataforge:data-file:query',2,5987,'dataforge/datafile/index'),
                (6421,'文件上传','dataforge:data-file:create',3,6420,NULL),
                (6422,'文件更新','dataforge:data-file:update',3,6420,NULL);
            """);
    }
    @AfterEach void cleanup() throws Exception {
        if(connection!=null)try(Connection owned=connection){
            execute("ROLLBACK");
            assertTrue(schema.matches("file_resource_[a-f0-9]{32}"));
            execute("DROP SCHEMA "+schema+" CASCADE");
        }
    }
    @Test void replacesLegacyTablesAndReplaysWithoutDuplicatingPermissions() throws Exception {
        execute(migration);
        assertEquals("2",value("SELECT count(*) FROM dh_dataforge_database_type WHERE category_code='FILE_OBJECT_STORAGE'"));
        assertEquals("文件管理",value("SELECT name FROM system_menu WHERE id=6420"));
        assertEquals("1",value("SELECT count(*) FROM system_menu WHERE permission='dataforge:data-file:download'"));
        assertNull(value("SELECT to_regclass('dh_dataforge_file')::text"));
        execute(migration);
        assertEquals("2",value("SELECT count(*) FROM dh_dataforge_database_type"));
        assertEquals("1",value("SELECT count(*) FROM system_menu WHERE permission='dataforge:data-file:download'"));
        assertEquals("0",value("SELECT count(*) FROM information_schema.columns WHERE table_schema='"+schema+"' AND table_name='dh_dataforge_file_operation' AND column_name='tenant_id'"));
    }
    @Test void auditRecordsUnknownResultsAndRejectsUnrecognizedAction() throws Exception {
        execute(migration);
        execute("INSERT INTO dh_dataforge_file_operation(connection_id,action,path,status) VALUES(9007199254740993,'UPLOAD','/a.txt','SUCCESS'),(9007199254740993,'UPLOAD','/b.txt','UNKNOWN')");
        assertEquals("1",value("SELECT count(*) FROM dh_dataforge_file_operation WHERE connection_id=9007199254740993 AND action='UPLOAD' AND status='SUCCESS' AND create_time>=CURRENT_DATE AND deleted=0"));
        assertThrows(SQLException.class,()->execute("INSERT INTO dh_dataforge_file_operation(connection_id,action,path,status) VALUES(1,'EXECUTE','/','SUCCESS')"));
    }
    @Test void missingMenuRollsBackDestructiveChanges() throws Exception {
        execute("DELETE FROM system_menu WHERE id=6420");
        assertThrows(SQLException.class,()->execute(migration));
        execute("ROLLBACK");
        assertEquals("1",value("SELECT count(*) FROM dh_dataforge_file"));
        assertEquals("0",value("SELECT count(*) FROM dh_dataforge_database_type"));
    }
    @Test void auditPathsAcceptTheFullComponentLimit() throws Exception {
        execute(migration);
        execute("INSERT INTO dh_dataforge_file_operation(connection_id,action,path,target_path,status) VALUES(1,'RENAME',repeat('p',4096),repeat('t',4096),'SUCCESS')");
        assertEquals("4096", value("SELECT length(path) FROM dh_dataforge_file_operation"));
        assertEquals("4096", value("SELECT length(target_path) FROM dh_dataforge_file_operation"));
        assertThrows(SQLException.class, () -> execute("INSERT INTO dh_dataforge_file_operation(connection_id,action,path,status) VALUES(1,'DELETE',repeat('p',4097),'PENDING')"));
    }
    private void execute(String sql)throws SQLException{try(Statement statement=connection.createStatement()){statement.execute(sql);}}
    private String value(String sql)throws SQLException{try(Statement statement=connection.createStatement();ResultSet rs=statement.executeQuery(sql)){assertTrue(rs.next());return rs.getString(1);}}
}

