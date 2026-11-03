package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseSqliteImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Real SQLite engine and separate database files; not an H2 dialect substitute. */
class SqliteDatabaseSqliteTest {
    @TempDir Path directory;
    private Connection connection;
    private final DatabaseSqliteImpl database = new DatabaseSqliteImpl();
    @BeforeEach void setup() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("main.db"));
        try (var statement = connection.prepareStatement("ATTACH DATABASE ? AS archive")) {
            statement.setString(1, directory.resolve("archive.db").toString()); statement.execute();
        }
        execute("CREATE TABLE main.orders (main_id INTEGER PRIMARY KEY, value TEXT DEFAULT 'a;b')");
        execute("CREATE TABLE archive.orders (archive_code TEXT NOT NULL, number INTEGER NOT NULL, PRIMARY KEY(number, archive_code)) WITHOUT ROWID");
        execute("CREATE TABLE archive.archive_only (id INTEGER)");
        execute("CREATE TEMP TABLE orders (temp_id INTEGER)");
        execute("CREATE VIEW main.report AS SELECT main_id FROM main.orders");
        execute("CREATE VIEW archive.report AS SELECT archive_code FROM orders");
    }
    @AfterEach void cleanup() throws Exception { if (connection != null) connection.close(); }
    private void execute(String sql) throws Exception { try (var statement = connection.createStatement()) { statement.execute(sql); } }

    @Test void nativeVersionIsRecorded() throws Exception {
        try (var s = connection.createStatement(); var r = s.executeQuery("SELECT sqlite_version()")) {
            assertTrue(r.next()); assertEquals("3.31.1", r.getString(1));
        }
    }
    @Test void attachedAndTempSchemasAreNotDiscarded() {
        assertTrue(database.querySchemaList(connection).containsAll(List.of("main", "temp", "archive")));
    }
    @Test void tableListingIsBoundToRequestedSchema() {
        var names = database.queryTableList(connection, "archive").stream().map(t -> t.getTableName()).toList();
        assertTrue(names.containsAll(List.of("orders", "archive_only", "report")));
        assertFalse(database.queryTableList(connection, "main").stream().anyMatch(t -> t.getTableName().equals("archive_only")));
        assertEquals(List.of("orders"), database.queryTableList(connection, "temp").stream().map(t -> t.getTableName()).toList());
    }
    @Test void ddlReadsRequestedDatabaseNotSameNamedMainOrTempObject() {
        assertTrue(database.getTableDDL(connection, "archive", "orders").contains("archive_code"));
        assertFalse(database.getTableDDL(connection, "archive", "orders").contains("main_id"));
        assertTrue(database.getTableDDL(connection, "temp", "orders").contains("temp_id"));
        assertTrue(database.getViewDDL(connection, "archive", "report").contains("archive_code"));
    }
    @Test void orderedCompositeKeysComeFromRequestedPhysicalObject() {
        assertEquals(List.of("number", "archive_code"), database.queryTablePrimaryKeys(connection, "archive", "orders"));
        assertEquals(List.of("main_id"), database.queryTablePrimaryKeys(connection, "main", "orders"));
        assertTrue(database.queryTablePrimaryKeys(connection, "temp", "orders").isEmpty());
    }
    @Test void quotesAndSemicolonsInIdentifiersRemainData() throws Exception {
        execute("CREATE TABLE archive.\"x\"\"; y\" (id INTEGER PRIMARY KEY, amount TEXT)");
        assertEquals("\"archive\".\"x\"\"; y\"", database.getQuotedSchemaTableCombination("archive", "x\"; y"));
        assertTrue(database.queryTableColumnMeta(connection, "archive", "x\"; y").stream().anyMatch(c -> c.getFieldName().equals("amount")));
        assertEquals(List.of("id"), database.queryTablePrimaryKeys(connection, "archive", "x\"; y"));
        assertTrue(database.getTableDDL(connection, "archive", "x\"; y").contains("amount"));
    }
    @Test void caseInsensitiveLookupAgreesWithNativeIdentifierResolution() {
        assertTrue(database.getTableDDL(connection, "archive", "ORDERS").contains("archive_code"));
    }
    @Test void ddlKeepsLiteralSemicolonsAndCanBeExecutedInFreshDatabase() throws Exception {
        String ddl = database.getTableDDL(connection, "main", "orders");
        try (var fresh = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("fresh.db")); var s = fresh.createStatement()) {
            s.execute(ddl); s.execute("INSERT INTO orders(main_id) VALUES(9007199254740993)");
            try (var r = s.executeQuery("SELECT main_id,value FROM orders")) {
                assertTrue(r.next()); assertEquals(9007199254740993L, r.getLong(1)); assertEquals("a;b", r.getString(2));
            }
        }
    }
    @Test void metadataCallsKeepCallerConnectionAndTransaction() throws Exception {
        execute("PRAGMA busy_timeout=1234");
        connection.setAutoCommit(false);
        execute("INSERT INTO main.orders(main_id) VALUES(42)");
        database.querySchemaList(connection); database.queryTableList(connection, "archive");
        database.getTableDDL(connection, "archive", "orders"); database.queryTablePrimaryKeys(connection, "archive", "orders");
        assertFalse(connection.isClosed()); assertFalse(connection.getAutoCommit()); connection.rollback();
        try (var s = connection.createStatement(); var r = s.executeQuery("PRAGMA busy_timeout")) {
            assertTrue(r.next()); assertEquals(1234, r.getInt(1));
        }
        try (var s = connection.createStatement(); var r = s.executeQuery("SELECT count(*) FROM main.orders")) {
            assertTrue(r.next()); assertEquals(0, r.getLong(1));
        }
    }

    @Test void generatedColumnsAndDefaultsRemainVisibleWithoutExecutingRows() throws Exception {
        execute("CREATE TABLE archive.generated (id INTEGER PRIMARY KEY, amount INTEGER DEFAULT 7, doubled INTEGER GENERATED ALWAYS AS (amount*2) STORED)");
        var columns = database.queryTableColumnMeta(connection, "archive", "generated");
        assertEquals(List.of("id", "amount", "doubled"), columns.stream().map(c -> c.getFieldName()).toList());
        assertTrue(columns.getFirst().isPk()); assertEquals("7", columns.get(1).getDefaultValue());
        assertEquals("'a;b'", database.queryTableColumnMeta(connection, "main", "orders").stream()
            .filter(c -> c.getFieldName().equals("value")).findFirst().orElseThrow().getDefaultValue());
    }

    @Test void invalidOrDetachedSchemasCannotFallBackToMain() throws Exception {
        assertThrows(RuntimeException.class, () -> database.queryTableList(connection, "not_attached"));
        assertThrows(RuntimeException.class, () -> database.getTableDDL(connection, "not_attached", "orders"));
        assertThrows(IllegalArgumentException.class, () -> database.getTableDDL(connection, "main\0archive", "orders"));
        execute("DETACH DATABASE archive");
        assertThrows(RuntimeException.class, () -> database.getTableDDL(connection, "archive", "orders"));
        assertFalse(database.querySchemaList(connection).contains("archive"));
    }

    @Test void qualifiedReadIgnoresTempShadowAndRetainsLargeIntegers() throws Exception {
        execute("INSERT INTO main.orders(main_id) VALUES(9007199254740993)");
        assertEquals("main_id", database.queryTableColumnMetaOnly(connection, "main", "orders").getFirst().getFieldName());
        try (var s = connection.createStatement(); var r = s.executeQuery("SELECT main_id FROM "
                + database.getQuotedSchemaTableCombination(null, "orders"))) {
            assertTrue(r.next()); assertEquals(9007199254740993L, r.getLong(1));
        }
    }

    @Test void nativeDeclarationsAreNotProofOfUniqueNonNullIntegerIdentity() throws Exception {
        execute("CREATE TABLE main.flexible (id INTEGER PRIMARY KEY DESC, value INTEGER)");
        execute("INSERT INTO main.flexible VALUES(NULL, 'text'),(NULL, 1.5)");
        assertEquals(List.of("id"), database.queryTablePrimaryKeys(connection, "main", "flexible"));
        try (var s = connection.createStatement(); var r = s.executeQuery("SELECT COUNT(*),COUNT(id),COUNT(DISTINCT typeof(value)) FROM main.flexible")) {
            assertTrue(r.next()); assertEquals(2, r.getLong(1)); assertEquals(0, r.getLong(2)); assertEquals(2, r.getLong(3));
        }
        assertThrows(SQLFeatureNotSupportedException.class, () -> database.queryRelationFingerprint(connection, "main.flexible"));
    }

    @Test void recreatedTableCanReuseCatalogIdentitySoNoGenerationIsInvented() throws Exception {
        String ddl = "CREATE TABLE identity_test (id INTEGER PRIMARY KEY)";
        execute(ddl);
        long first;
        try (var s = connection.createStatement(); var r = s.executeQuery("SELECT rootpage FROM main.sqlite_master WHERE name='identity_test'")) {
            assertTrue(r.next()); first = r.getLong(1);
        }
        execute("DROP TABLE main.identity_test"); execute(ddl);
        try (var s = connection.createStatement(); var r = s.executeQuery("SELECT rootpage,sql FROM main.sqlite_master WHERE name='identity_test'")) {
            assertTrue(r.next()); assertEquals(first, r.getLong(1)); assertEquals(ddl, r.getString(2));
        }
        assertThrows(SQLFeatureNotSupportedException.class, () -> database.queryRelationFingerprint(connection, "main.identity_test"));
    }
}
