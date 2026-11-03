package cn.iocoder.yudao.framework.dbswitch.dbwriter.pg;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 使用真实 PostgreSQL 验证提交重放、回滚重放与目标唯一键拒绝边界。 */
@EnabledIfEnvironmentVariable(named="DATAHUB_UPSERT_TEST_URL", matches="jdbc:postgresql://127[.]0[.]0[.]1:55427/.*")
class PostgresUpsertSqlTest {
    private Connection connection;
    private final String schema = "upsert_test_" + UUID.randomUUID().toString().replace("-", "");
    @BeforeEach void setup() throws Exception {
        connection=DriverManager.getConnection(System.getenv("DATAHUB_UPSERT_TEST_URL"), "postgres", System.getenv("DATAHUB_UPSERT_TEST_PASSWORD"));
        execute("CREATE SCHEMA " + schema);
    }
    @AfterEach void cleanup() throws Exception {
        if (!connection.getAutoCommit()) connection.rollback();
        connection.setAutoCommit(true); execute("DROP SCHEMA " + schema + " CASCADE"); connection.close();
    }
    private void execute(String sql) throws Exception { try(var s=connection.createStatement()){s.execute(sql);} }
    private void verify(String table, List<String> keys) throws Exception { PostgresUpsertSql.verifyUniqueKey(connection,schema,table,keys,5); }
    private String sql() {return PostgresUpsertSql.sql(schema,"rows",List.of("scope","id","value"),List.of("scope","id"),List.of("?","?","?"));}
    private int write(int id, String value) throws Exception {
        try(var s=connection.prepareStatement(sql())){s.setString(1,"a");s.setInt(2,id);s.setString(3,value);return s.executeUpdate();}
    }
    @Test void commitReplayAndRollbackReplayPreserveExactRows() throws Exception {
        execute("CREATE TABLE " + schema + ".rows(scope text NOT NULL,id int NOT NULL,value text,PRIMARY KEY(scope,id))");
        verify("rows", List.of("scope","id")); connection.setAutoCommit(false);
        assertEquals(1,write(1," apostrophe ' 中文 ")); connection.commit();
        assertEquals(0,write(1," apostrophe ' 中文 ")); write(2,"uncommitted"); connection.rollback();
        assertEquals(0,write(1," apostrophe ' 中文 ")); write(2,"final"); connection.commit();
        try(var s=connection.createStatement();var r=s.executeQuery("SELECT count(*),count(DISTINCT (scope,id)) FROM "+schema+".rows")){r.next();assertEquals(2,r.getInt(1));assertEquals(2,r.getInt(2));}
        assertEquals(1,write(2,"changed"));connection.commit();assertEquals(0,write(2,"changed"));connection.commit();
    }
    @Test void nullablePartialExpressionAndDeferredKeysAreRefused() throws Exception {
        for(String ddl:List.of("id int UNIQUE", "id int NOT NULL UNIQUE DEFERRABLE", "id int NOT NULL")){
            execute("CREATE TABLE "+schema+".unsafe("+ddl+")");assertThrows(SQLException.class,()->verify("unsafe",List.of("id")));execute("DROP TABLE "+schema+".unsafe");
        }
        execute("CREATE TABLE "+schema+".unsafe(id int NOT NULL)");execute("CREATE UNIQUE INDEX partial_key ON "+schema+".unsafe(id) WHERE id>0");
        assertThrows(SQLException.class,()->verify("unsafe",List.of("id")));
        execute("CREATE UNIQUE INDEX expression_key ON "+schema+".unsafe((id+1))");assertThrows(SQLException.class,()->verify("unsafe",List.of("id")));
    }
    @Test void conflictOutsideSelectedKeyFailsWithoutLosingTheBatch() throws Exception {
        execute("CREATE TABLE "+schema+".rows(scope text NOT NULL,id int NOT NULL,value text UNIQUE,PRIMARY KEY(scope,id))");
        connection.setAutoCommit(false);write(1,"same");connection.commit();write(2,"new");
        assertThrows(SQLException.class,()->write(3,"same"));connection.rollback();
        try(var s=connection.createStatement();var r=s.executeQuery("SELECT count(*) FROM "+schema+".rows")){r.next();assertEquals(1,r.getInt(1));}
    }
    @Test void identifiersAreQuotedAndKeysMustBeIncluded() {
        assertTrue(PostgresUpsertSql.sql("a\"b","t",List.of("select","v"),List.of("select"),List.of("?","?")).contains("\"a\"\"b\".\"t\""));
        assertThrows(IllegalArgumentException.class,()->PostgresUpsertSql.sql(schema,"t",List.of("v"),List.of("id"),List.of("?")));
        assertThrows(IllegalArgumentException.class,()->PostgresUpsertSql.sql(schema,"t",List.of("id","id"),List.of("id"),List.of("?","?")));
    }
}
