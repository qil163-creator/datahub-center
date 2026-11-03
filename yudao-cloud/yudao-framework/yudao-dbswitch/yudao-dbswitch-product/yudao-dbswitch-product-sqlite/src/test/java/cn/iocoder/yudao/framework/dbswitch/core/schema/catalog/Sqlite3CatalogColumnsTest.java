package cn.iocoder.yudao.framework.dbswitch.core.schema.catalog;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableSchemaMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.schema.NativeSchemaReader;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TypeMappingIssue;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TypeMappingResult;
import cn.iocoder.yudao.framework.dbswitch.product.sqlite3.Sqlite3CatalogColumns;
import cn.iocoder.yudao.framework.dbswitch.product.sqlite3.Sqlite3LogicalTypeMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQLite 3.31.1 file catalog. The temporary database is the test directory, not a stand-in for getColumns.
 */
@Timeout(60)
class Sqlite3CatalogColumnsTest {
    private static final Sqlite3LogicalTypeMapper MAPPER = new Sqlite3LogicalTypeMapper();

    @TempDir Path directory;

    @BeforeAll static void driver() throws ClassNotFoundException { Class.forName("org.sqlite.JDBC"); }

    @Test void declaredTypeComesFromPragmaAndNotFromGetColumns() throws Exception {
        try (var connection = open()) {
            exec(connection, """
                    CREATE TABLE affinity(
                      c_integer INTEGER,
                      c_real REAL,
                      c_text TEXT,
                      c_blob BLOB,
                      c_numeric NUMERIC,
                      c_varchar VARCHAR(20),
                      c_varchar0 VARCHAR(0),
                      c_char CHAR(5),
                      c_decimal DECIMAL(12,2),
                      c_decimal_sp decimal(12, 2),
                      c_decimal_wide DECIMAL(5,9),
                      c_boolean BOOLEAN,
                      c_date DATE,
                      c_datetime DATETIME,
                      c_int INT,
                      c_empty ,
                      c_lower integer,
                      c_default INTEGER DEFAULT (2+3)
                    )""");
            var metadata = Sqlite3CatalogColumns.read(connection, "main", "affinity", 10);
            assertTrue(metadata.provenance().nativeProduct(ProductTypeEnum.SQLITE3));
            assertEquals("SQLITE_CATALOG_V1", metadata.provenance().method());
            assertEquals("3.31.1", metadata.provenance().serverVersion());
            assertEquals(MetadataValue.State.VALUE, metadata.existence().state());
            assertEquals(MetadataValue.State.ABSENT, metadata.additionalObjects().state());
            assertTrue(metadata.primaryKeys().isEmpty());

            absentNumbers(column(metadata, "c_integer"), Types.INTEGER, "INTEGER", true);
            assertEquals("LOSSY", fidelity(column(metadata, "c_integer")));
            assertEquals("DECLARED_TYPE_AFFINITY_NOT_STRICT,INT64_OVERFLOW_STORED_AS_REAL", codes(column(metadata, "c_integer")));
            absentNumbers(column(metadata, "c_real"), Types.REAL, "REAL", true);
            absentNumbers(column(metadata, "c_text"), Types.VARCHAR, "TEXT", false);
            absentNumbers(column(metadata, "c_blob"), Types.BLOB, "BLOB", false);
            assertEquals("BLOB_AFFINITY_MIXED_STORAGE", codes(column(metadata, "c_blob")));
            absentNumbers(column(metadata, "c_numeric"), Types.NUMERIC, "NUMERIC", true);
            assertEquals("NUMERIC_AFFINITY_MIXED_STORAGE", codes(column(metadata, "c_numeric")));
            absentNumbers(column(metadata, "c_boolean"), Types.BOOLEAN, "BOOLEAN", false);
            absentNumbers(column(metadata, "c_date"), Types.DATE, "DATE", false);
            absentNumbers(column(metadata, "c_datetime"), Types.DATE, "DATETIME", false);
            absentNumbers(column(metadata, "c_lower"), Types.INTEGER, "integer", true);
            assertEquals("integer", evidence(column(metadata, "c_lower")).nativeType().value());

            var varchar = column(metadata, "c_varchar");
            assertEquals("VARCHAR(20)", evidence(varchar).nativeType().value());
            assertEquals(20, varchar.getPrecisionSize());
            assertEquals(MetadataValue.State.ABSENT, evidence(varchar).numericPrecision().state());
            assertEquals(MetadataValue.State.ABSENT, evidence(varchar).numericScale().state());
            assertEquals(20L, evidence(varchar).characterLength().value());
            assertEquals("DECLARED_TYPE_AFFINITY_NOT_STRICT,LENGTH_NOT_ENFORCED", codes(varchar));

            var zero = column(metadata, "c_varchar0");
            assertEquals(0, zero.getPrecisionSize());
            assertEquals(0L, evidence(zero).characterLength().value());
            assertEquals("ZERO_LENGTH_NOT_A_DOMAIN", codes(zero));

            var character = column(metadata, "c_char");
            assertEquals(Types.CHAR, character.getFieldType());
            assertEquals(5L, evidence(character).characterLength().value());
            assertEquals(MetadataValue.State.ABSENT, evidence(character).numericPrecision().state());

            var spaced = column(metadata, "c_decimal_sp");
            assertEquals("decimal(12, 2)", evidence(spaced).nativeType().value());
            assertFalse(spaced.isSigned());
            assertEquals(12, evidence(spaced).numericPrecision().value());
            assertEquals(2, evidence(spaced).numericScale().value());
            assertEquals(MetadataValue.State.ABSENT, evidence(spaced).characterLength().state());
            assertEquals("DECIMAL_STORED_AS_REAL_OR_TEXT", codes(spaced));
            var wide = column(metadata, "c_decimal_wide");
            assertEquals(5, evidence(wide).numericPrecision().value());
            assertEquals(9, evidence(wide).numericScale().value());
            assertEquals("DECIMAL_STORED_AS_REAL_OR_TEXT", codes(wide));

            var blank = column(metadata, "c_empty");
            assertEquals("", evidence(blank).nativeType().value());
            assertEquals(Types.NUMERIC, blank.getFieldType());
            assertEquals("NATIVE_TYPE_UNRECOGNIZED", codes(blank));
            assertEquals("NATIVE_TYPE_UNRECOGNIZED", codes(column(metadata, "c_int")));
            var literalDefault = column(metadata, "c_default");
            assertEquals("2+3", evidence(literalDefault).defaultExpression().value());
            assertEquals("2+3", literalDefault.getDefaultValue());

            try (var columns = connection.getMetaData().getColumns(null, null, "affinity", null)) {
                boolean blob = false;
                boolean numeric = false;
                while (columns.next()) {
                    if ("c_blob".equals(columns.getString("COLUMN_NAME"))) {
                        blob = true;
                        assertEquals(Types.VARCHAR, columns.getInt("DATA_TYPE"));
                    }
                    if ("c_numeric".equals(columns.getString("COLUMN_NAME"))) {
                        numeric = true;
                        assertEquals(Types.FLOAT, columns.getInt("DATA_TYPE"));
                    }
                }
                assertTrue(blob && numeric);
            }
            assertThrows(SQLException.class, () -> exec(connection, "SELECT * FROM information_schema.columns"));
        }
    }

    @Test void collateKeywordIsUnknownAndAStringLiteralIsNot() throws Exception {
        try (var connection = open()) {
            exec(connection, "CREATE TABLE collated(a TEXT COLLATE NOCASE, b INTEGER, note TEXT NOT NULL COLLATE BINARY)");
            var collated = Sqlite3CatalogColumns.read(connection, "main", "collated", 10);
            for (var column : collated.columns()) {
                assertEquals(MetadataValue.State.UNKNOWN, evidence(column).collation().state());
                assertEquals(MetadataValue.State.ABSENT, evidence(column).charset().state());
            }
            assertEquals("INCOMPLETE_METADATA", codes(column(collated, "b")));
            var note = column(collated, "note");
            assertEquals("TEXT", evidence(note).nativeType().value());
            assertEquals(false, evidence(note).nullable().value());
            assertTrue(note.isNullable());
            assertNotEquals(note.isNullable(), evidence(note).nullable().value());

            exec(connection, "CREATE TABLE literal_word(note TEXT DEFAULT 'COLLATE')");
            var literal = Sqlite3CatalogColumns.read(connection, "main", "literal_word", 10);
            assertEquals(MetadataValue.State.ABSENT, evidence(column(literal, "note")).collation().state());
            assertEquals("'COLLATE'", evidence(column(literal, "note")).defaultExpression().value());
            assertEquals("DECLARED_TYPE_AFFINITY_NOT_STRICT", codes(column(literal, "note")));

            exec(connection, "CREATE TABLE table_collate(id INTEGER, note TEXT, CHECK(note COLLATE NOCASE <> 'x'))");
            var checked = Sqlite3CatalogColumns.read(connection, "main", "table_collate", 10);
            assertEquals(MetadataValue.State.UNKNOWN, evidence(column(checked, "id")).collation().state());
            assertEquals(MetadataValue.State.UNKNOWN, checked.additionalObjects().state());
        }
    }

    @Test void primaryKeyOrdinalIsNotAStrictIntegerDomain() throws Exception {
        try (var connection = open()) {
            exec(connection, "CREATE TABLE plain(id INTEGER PRIMARY KEY, note TEXT)");
            exec(connection, "CREATE TABLE comp(a INTEGER, b TEXT, PRIMARY KEY(b, a))");
            exec(connection, "CREATE TABLE auto(id INTEGER PRIMARY KEY AUTOINCREMENT, note TEXT)");
            var plain = Sqlite3CatalogColumns.read(connection, "main", "plain", 10);
            var composite = Sqlite3CatalogColumns.read(connection, "main", "comp", 10);
            var auto = Sqlite3CatalogColumns.read(connection, "main", "auto", 10);

            assertEquals(java.util.List.of("id"), plain.primaryKeys());
            var plainId = column(plain, "id");
            assertTrue(plainId.isPk());
            assertFalse(plainId.isNullable());
            assertEquals(true, evidence(plainId).nullable().value());
            assertEquals("INTEGER", evidence(plainId).nativeType().value());
            assertEquals(MetadataValue.State.ABSENT, evidence(plainId).identity().state());
            assertFalse(plainId.isAutoIncrement());
            assertEquals("DECLARED_TYPE_AFFINITY_NOT_STRICT,INT64_OVERFLOW_STORED_AS_REAL", codes(plainId));

            assertEquals(java.util.List.of("b", "a"), composite.primaryKeys());
            var compositeInteger = column(composite, "a");
            assertTrue(compositeInteger.isPk());
            assertEquals("INTEGER", evidence(compositeInteger).nativeType().value());
            assertEquals(codes(plainId), codes(compositeInteger));
            assertEquals(MetadataValue.State.VALUE, composite.additionalObjects().state());
            assertEquals(java.util.List.of("index:sqlite_autoindex_comp_1"), composite.additionalObjects().value());
            assertEquals(MetadataValue.State.ABSENT, plain.additionalObjects().state());

            var generated = column(auto, "id");
            assertTrue(generated.isAutoIncrement());
            assertEquals(MetadataValue.State.ABSENT, evidence(generated).identity().state());
            assertEquals(MetadataValue.State.ABSENT, evidence(generated).sequence().state());
            assertFalse(generated.isNullable());
            assertEquals(true, evidence(generated).nullable().value());
        }
    }

    @Test void additionalObjectsFollowSeparateIndexAndTriggerQueries() throws Exception {
        try (var connection = open()) {
            exec(connection, "CREATE TABLE uniq(id INTEGER, note TEXT UNIQUE, amount INTEGER)");
            exec(connection, "CREATE INDEX uniq_amount ON uniq(amount)");
            exec(connection, "CREATE TRIGGER uniq_bi BEFORE INSERT ON uniq BEGIN SELECT 1; END");
            exec(connection, "CREATE TABLE checked(id INTEGER CHECK(id > 0), note TEXT)");
            exec(connection, "CREATE INDEX checked_note ON checked(note)");
            exec(connection, "CREATE TABLE wide(a INTEGER PRIMARY KEY) WITHOUT ROWID");
            exec(connection, "CREATE TABLE main_only(id INTEGER)");
            exec(connection, "CREATE INDEX main_only_id ON main_only(id)");
            exec(connection, "CREATE TEMP TABLE temp_only(id INTEGER, note VARCHAR(4))");
            exec(connection, "CREATE INDEX temp.temp_only_id ON temp_only(id)");
            exec(connection, "CREATE TRIGGER temp.temp_only_bi BEFORE INSERT ON temp_only BEGIN SELECT 1; END");

            var unique = Sqlite3CatalogColumns.read(connection, "main", "uniq", 10);
            assertEquals(java.util.List.of("index:sqlite_autoindex_uniq_1", "index:uniq_amount", "trigger:uniq_bi"),
                    unique.additionalObjects().value());
            var constraint = Sqlite3CatalogColumns.read(connection, "main", "checked", 10);
            assertEquals(MetadataValue.State.UNKNOWN, constraint.additionalObjects().state());
            assertNull(constraint.additionalObjects().value());
            var without = Sqlite3CatalogColumns.read(connection, "main", "wide", 10);
            assertEquals(MetadataValue.State.UNKNOWN, without.additionalObjects().state());
            assertEquals(java.util.List.of("a"), without.primaryKeys());

            var temporary = Sqlite3CatalogColumns.read(connection, "temp", "temp_only", 10);
            assertEquals(4L, evidence(column(temporary, "note")).characterLength().value());
            assertEquals(java.util.List.of("index:temp_only_id", "trigger:temp_only_bi"), temporary.additionalObjects().value());
            assertFalse(temporary.additionalObjects().value().contains("index:main_only_id"));
        }
    }

    @Test void generatedColumnsStayOnTheXinfoList() throws Exception {
        try (var connection = open()) {
            exec(connection, "CREATE TABLE gen(id INTEGER, doubled INTEGER GENERATED ALWAYS AS (id*2) STORED, virt INTEGER GENERATED ALWAYS AS (id+1) VIRTUAL)");
            var metadata = Sqlite3CatalogColumns.read(connection, "main", "gen", 10);
            assertEquals(java.util.List.of("id", "doubled", "virt"), metadata.columns().stream().map(ColumnDescription::getFieldName).toList());
            assertEquals(MetadataValue.State.ABSENT, evidence(column(metadata, "id")).generatedExpression().state());
            assertEquals("INTEGER GENERATED ALWAYS", evidence(column(metadata, "doubled")).nativeType().value());
            assertEquals(MetadataValue.State.UNKNOWN, evidence(column(metadata, "doubled")).generatedExpression().state());
            assertEquals(MetadataValue.State.UNKNOWN, evidence(column(metadata, "virt")).generatedExpression().state());
            assertEquals(MetadataValue.State.ABSENT, evidence(column(metadata, "doubled")).characterLength().state());
            assertEquals("NATIVE_TYPE_UNRECOGNIZED", codes(column(metadata, "doubled")));
        }
    }

    @Test void busyTimeoutIsRestoredAndTheSharedReaderUsesThePragmaCatalog() throws Exception {
        try (var connection = open()) {
            try (var statement = connection.createStatement()) { statement.execute("PRAGMA busy_timeout=1111"); }
            assertThrows(SQLException.class, () -> Sqlite3CatalogColumns.read(connection, "main", "missing", 10));
            try (var rows = connection.createStatement().executeQuery("PRAGMA busy_timeout")) {
                assertTrue(rows.next());
                assertEquals(1111L, rows.getLong(1));
            }
            exec(connection, "CREATE TABLE plain(id INTEGER)");
            try (var statement = connection.createStatement()) { statement.execute("PRAGMA busy_timeout=2222"); }
            var direct = Sqlite3CatalogColumns.read(connection, "main", "plain", 10);
            var shared = NativeSchemaReader.read(connection, ProductTypeEnum.SQLITE3, "main", "plain", 10);
            try (var rows = connection.createStatement().executeQuery("PRAGMA busy_timeout")) {
                assertTrue(rows.next());
                assertEquals(2222L, rows.getLong(1));
            }
            // pragma_table_xinfo, not DatabaseMetaData.getColumns. No pk column was declared, so the key list is empty
            // because the pragma was read. This table has no index, trigger, CHECK, REFERENCES or WITHOUT ROWID.
            assertEquals("SQLITE_CATALOG_V1", shared.provenance().method());
            assertEquals(direct.primaryKeys(), shared.primaryKeys());
            assertTrue(shared.primaryKeys().isEmpty());
            assertEquals(direct.additionalObjects(), shared.additionalObjects());
            assertEquals(MetadataValue.State.ABSENT, shared.additionalObjects().state());
            assertEquals("INTEGER", evidence(column(shared, "id")).nativeType().value());
        }
    }

    @Test void timeoutAndNamesAreRejectedBeforeACatalogClaim() {
        assertThrows(IllegalArgumentException.class, () -> Sqlite3CatalogColumns.read(null, "main", "plain", 0));
    }

    @Test void jdbcVersionCannotReplaceTheObservedEngineVersion() throws Exception {
        try (var actual = open()) {
            var jdbc = org.mockito.Mockito.mock(java.sql.DatabaseMetaData.class);
            org.mockito.Mockito.when(jdbc.getDatabaseProductName()).thenReturn("SQLite");
            org.mockito.Mockito.when(jdbc.getDatabaseProductVersion()).thenReturn("3.49.1");
            var facade = org.mockito.Mockito.mock(Connection.class);
            org.mockito.Mockito.when(facade.getMetaData()).thenReturn(jdbc);
            org.mockito.Mockito.when(facade.createStatement()).thenAnswer(ignored -> actual.createStatement());
            var failure = assertThrows(java.sql.SQLFeatureNotSupportedException.class,
                    () -> NativeSchemaReader.read(facade, ProductTypeEnum.SQLITE3, "main", "plain", 10));
            assertEquals("0A000", failure.getSQLState());
        }
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("catalog.db").toAbsolutePath());
    }

    private static void exec(Connection connection, String sql) throws SQLException { connection.createStatement().execute(sql); }

    private static ColumnDescription column(TableSchemaMetadata metadata, String name) {
        return metadata.columns().stream().filter(column -> name.equals(column.getFieldName())).findFirst().orElseThrow();
    }

    private static ColumnMetadataEvidence evidence(ColumnDescription column) { return column.getMetadataEvidence(); }

    private static void absentNumbers(ColumnDescription column, int jdbc, String typeName, boolean signed) {
        var facts = evidence(column);
        assertEquals(typeName, facts.nativeType().value());
        assertEquals(jdbc, column.getFieldType());
        assertEquals(typeName.equals("integer") ? "INTEGER" : typeName, column.getFieldTypeName());
        assertEquals("java.lang.Object", column.getFiledTypeClassName());
        assertEquals(Integer.MAX_VALUE, column.getDisplaySize());
        assertEquals(signed, column.isSigned());
        assertEquals(0, column.getPrecisionSize());
        assertEquals(0, column.getScaleSize());
        assertTrue(column.isNullable());
        assertEquals(true, facts.nullable().value());
        assertEquals(MetadataValue.State.ABSENT, facts.numericPrecision().state());
        assertEquals(MetadataValue.State.ABSENT, facts.numericScale().state());
        assertEquals(MetadataValue.State.ABSENT, facts.characterLength().state());
        assertEquals(MetadataValue.State.ABSENT, facts.charset().state());
        assertEquals(MetadataValue.State.ABSENT, facts.collation().state());
        assertEquals(MetadataValue.State.ABSENT, facts.identity().state());
        assertEquals(MetadataValue.State.ABSENT, facts.generatedExpression().state());
        assertEquals(MetadataValue.State.ABSENT, facts.sequence().state());
        assertEquals(MetadataValue.State.ABSENT, facts.defaultExpression().state());
        assertNull(column.getDefaultValue());
        assertEquals(ResultSetMetaData.columnNullable, 1);
    }

    private static String fidelity(ColumnDescription column) { return MAPPER.toLogical(column).fidelity().name(); }

    private static String codes(ColumnDescription column) {
        TypeMappingResult<?> result = MAPPER.toLogical(column);
        return result.issues().stream().map(TypeMappingIssue::code).sorted().collect(Collectors.joining(","));
    }
}
