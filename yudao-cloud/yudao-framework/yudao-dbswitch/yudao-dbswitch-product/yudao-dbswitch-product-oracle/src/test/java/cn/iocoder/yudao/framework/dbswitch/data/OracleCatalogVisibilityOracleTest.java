package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.core.util.OracleSqlNames;
import cn.iocoder.yudao.framework.dbswitch.product.oracle.OracleNativeTables;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Actual cross-owner SELECT grants; an empty catalog must not silently omit dependent behavior. */
@Timeout(60)
@EnabledIfSystemProperty(named = "datahub.test.oracle.url", matches = "jdbc:oracle:thin:@//127[.]0[.]0[.]1:[0-9]+/FREEPDB1")
class OracleCatalogVisibilityOracleTest {
    private Connection admin;
    private String owner, reader;
    private boolean ownerCreated, readerCreated;
    private DriverManagerDataSource ownerSource, readerSource;

    @BeforeEach void setup() throws SQLException {
        String url = System.getProperty("datahub.test.oracle.url");
        admin = DriverManager.getConnection(url, "SYSTEM", System.getenv("DATAHUB_TEST_ORACLE_PASSWORD"));
        assertEquals("Oracle", admin.getMetaData().getDatabaseProductName());
        owner = "CV_O_" + suffix();
        reader = "CV_R_" + suffix();
        String secret = "Cv" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE USER " + quote(owner) + " IDENTIFIED BY " + secret
                + " DEFAULT TABLESPACE CODEX_Q11 QUOTA 10M ON CODEX_Q11");
        ownerCreated = true;
        sql("CREATE USER " + quote(reader) + " IDENTIFIED BY " + secret);
        readerCreated = true;
        sql("GRANT CREATE SESSION, CREATE TABLE TO " + quote(owner));
        sql("GRANT CREATE SESSION TO " + quote(reader));
        sql("CREATE TABLE " + relation("T") + " (ID NUMBER(10,0) PRIMARY KEY, V VARCHAR2(20 CHAR))");
        sql("INSERT INTO " + relation("T") + " VALUES (1,'visible')");
        sql("GRANT SELECT ON " + relation("T") + " TO " + quote(reader));
        ownerSource = new DriverManagerDataSource(url, owner, secret);
        readerSource = new DriverManagerDataSource(url, reader, secret);
    }

    @AfterEach void cleanup() throws SQLException {
        if (admin == null) return;
        try (var ignored = admin) {
            try {
                if (readerCreated) sql("DROP USER " + quote(reader) + " CASCADE");
            } finally {
                if (ownerCreated) sql("DROP USER " + quote(owner) + " CASCADE");
            }
        }
    }

    @Test void selectOnlyAccountCanCaptureAndMapAnOrdinaryTable() throws SQLException {
        sql("COMMENT ON TABLE " + relation("T") + " IS '跨账号结构'");
        var evidence = OracleNativeTables.describe(readerSource, owner, "T", 15);
        assertEquals(java.util.List.of("ID", "V"), evidence.fields());
        var plan = OracleNativeTables.plan(ownerSource, evidence, owner, "COPY_T",
                Map.of("ID", "COPY_ID", "V", "COPY_V"), false, 15);
        plan.execute();
        try (var connection = ownerSource.getConnection()) {
            assertEquals(2, count(connection, "SELECT COUNT(*) FROM sys.user_tab_columns WHERE table_name='COPY_T'"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM sys.user_constraints WHERE table_name='COPY_T' AND constraint_type='P'"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM sys.user_tab_comments WHERE table_name='COPY_T' AND comments='跨账号结构'"));
        }
    }

    @Test void triggerVisibleThroughSelectGrantCannotBeSilentlyDroppedFromEvidence() throws SQLException {
        sql("CREATE TRIGGER " + relation("TR") + " BEFORE INSERT ON " + relation("T")
                + " FOR EACH ROW BEGIN :NEW.V := 'changed'; END;");
        try (var connection = readerSource.getConnection()) {
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM sys.all_triggers WHERE table_owner='" + owner + "' AND table_name='T'"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM " + relation("T")));
        }
        assertThrows(IllegalStateException.class, () -> OracleNativeTables.describe(readerSource, owner, "T", 15));
    }

    @Test void rowPolicyVisibleThroughSelectGrantCannotBeCapturedAsAnUnrestrictedTable() throws SQLException {
        sql("CREATE FUNCTION " + relation("PF")
                + " (s VARCHAR2, t VARCHAR2) RETURN VARCHAR2 AS BEGIN RETURN 'ID=1'; END;");
        sql("BEGIN DBMS_RLS.ADD_POLICY(object_schema=>'" + owner
                + "',object_name=>'T',policy_name=>'P',function_schema=>'" + owner
                + "',policy_function=>'PF',statement_types=>'SELECT'); END;");
        try (var connection = readerSource.getConnection()) {
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM sys.all_policies WHERE object_owner='" + owner + "' AND object_name='T'"));
        }
        assertThrows(IllegalStateException.class, () -> OracleNativeTables.describe(readerSource, owner, "T", 15));
    }

    @Test void secondaryIndexVisibleThroughSelectGrantIsRetainedByTheIndexPlan() throws SQLException {
        sql("CREATE INDEX " + relation("SECONDARY_V") + " ON " + relation("T") + "(V)");
        try (var connection = readerSource.getConnection()) {
            assertEquals(2, count(connection, "SELECT COUNT(*) FROM sys.all_indexes WHERE table_owner='" + owner + "' AND table_name='T'"));
        }
        var evidence = OracleNativeTables.describe(readerSource, owner, "T", 15);
        OracleNativeTables.plan(ownerSource, evidence, owner, "COPY_T",
                Map.of("ID", "COPY_ID", "V", "COPY_V"), false, 15).execute();
        try (var connection = ownerSource.getConnection()) {
            assertEquals(2, count(connection, "SELECT COUNT(*) FROM sys.user_indexes WHERE table_name='COPY_T'"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM sys.user_ind_columns c JOIN sys.user_indexes i ON i.index_name=c.index_name WHERE i.table_name='COPY_T' AND c.column_name='COPY_V' AND c.column_position=1 AND i.uniqueness='NONUNIQUE'"));
        }
    }

    private void sql(String sql) throws SQLException {
        try (var statement = admin.createStatement()) { statement.setQueryTimeout(15); statement.execute(sql); }
    }

    private int count(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(15);
            try (var rows = statement.executeQuery(sql)) { assertTrue(rows.next()); return rows.getInt(1); }
        }
    }

    private String relation(String table) { return OracleSqlNames.relation(owner, table); }
    private String quote(String name) { return OracleSqlNames.quote(name); }
    private static String suffix() { return UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT); }
}
