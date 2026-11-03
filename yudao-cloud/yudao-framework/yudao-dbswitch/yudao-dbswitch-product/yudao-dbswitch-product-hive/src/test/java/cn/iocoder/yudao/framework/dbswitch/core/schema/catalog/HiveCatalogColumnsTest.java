package cn.iocoder.yudao.framework.dbswitch.core.schema.catalog;

import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TypeFidelity;
import cn.iocoder.yudao.framework.dbswitch.product.hive.HiveCatalogColumns;
import cn.iocoder.yudao.framework.dbswitch.product.hive.HiveLogicalTypeMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Hive 4.1.0 TEXTFILE catalog alignment. DESCRIBE facts are not read from information_schema. */
@Timeout(60)
class HiveCatalogColumnsTest {
    private static final String TEXT_INPUT = "org.apache.hadoop.mapred.TextInputFormat";
    private static final String LAZY_SERDE = "org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe";

    @Test void alignsQualifiedJdbcNamesToUnqualifiedDescribeAndOmitsMysqlCatalog() throws Exception {
        var executed = new ArrayList<String>();
        Connection connection = connection(executed, formatted("EXTERNAL_TABLE", TEXT_INPUT, LAZY_SERDE),
                properties("numFiles", "0"),
                metadata(
                        jdbc("s.plain.c_int", Types.INTEGER, "int", "java.lang.Integer", 10, 0, true),
                        jdbc("plain.c_string", Types.VARCHAR, "string", "java.lang.String", 2147483647, 0, false),
                        jdbc("c_bin", Types.BINARY, "binary", "[B", 2147483647, 0, false),
                        jdbc("plain.c_char", Types.CHAR, "char", "java.lang.String", 5, 0, false),
                        jdbc("plain.c_varchar", Types.VARCHAR, "varchar", "java.lang.String", 65535, 0, false),
                        jdbc("plain.c_dec", Types.DECIMAL, "decimal", "java.math.BigInteger", 10, 2, false)),
                describe(
                        row("c_string", "string", "note"),
                        row("c_bin", "binary   ", null),
                        row("c_char", "char(5)", ""),
                        row("c_varchar", "varchar(65535)", null),
                        row("c_dec", "decimal(10,2)", null),
                        row("c_int", "int", null)));
        List<ColumnDescription> columns = HiveCatalogColumns.read(connection, "s", "plain", 30);
        assertEquals(List.of(
                "SELECT version()",
                "DESCRIBE FORMATTED `s`.`plain`",
                "SHOW TBLPROPERTIES `s`.`plain`",
                "SELECT * FROM `s`.`plain` WHERE 1=0",
                "DESCRIBE `s`.`plain`"), executed);
        assertTrue(executed.stream().noneMatch(sql -> sql.toLowerCase().contains("information_schema")
                || sql.contains("COLUMN_NAME") || sql.contains("MYSQL_COLUMNS")));
        assertEquals(List.of("c_int", "c_string", "c_bin", "c_char", "c_varchar", "c_dec"),
                columns.stream().map(ColumnDescription::getFieldName).toList());
        assertEquals("note", columns.get(1).getRemarks());
        assertNull(columns.get(2).getRemarks());
        ColumnMetadataEvidence string = columns.get(1).getMetadataEvidence();
        assertEquals(MetadataValue.State.ABSENT, string.numericPrecision().state());
        assertEquals(MetadataValue.State.ABSENT, string.numericScale().state());
        assertEquals(MetadataValue.State.ABSENT, string.characterLength().state());
        assertEquals(MetadataValue.State.ABSENT, string.charset().state());
        assertEquals(MetadataValue.State.ABSENT, string.collation().state());
        assertEquals(MetadataValue.State.UNKNOWN, string.defaultExpression().state());
        assertEquals(MetadataValue.State.UNKNOWN, string.identity().state());
        assertEquals(MetadataValue.State.UNKNOWN, string.generatedExpression().state());
        assertEquals(MetadataValue.State.UNKNOWN, string.sequence().state());
        assertEquals(MetadataValue.State.VALUE, string.nullable().state());
        assertEquals(Boolean.TRUE, string.nullable().value());
        assertEquals("Apache Hive", string.provenance().serverProduct());
        assertEquals(HiveCatalogColumns.VERSION, string.provenance().serverVersion());
        assertEquals("HIVE_CATALOG_V1", string.provenance().method());
        ColumnMetadataEvidence binary = columns.get(2).getMetadataEvidence();
        assertEquals(MetadataValue.State.ABSENT, binary.numericPrecision().state());
        assertEquals(MetadataValue.State.ABSENT, binary.numericScale().state());
        assertEquals(5L, columns.get(3).getMetadataEvidence().characterLength().value());
        assertEquals(MetadataValue.State.ABSENT, columns.get(3).getMetadataEvidence().numericPrecision().state());
        assertEquals(65535L, columns.get(4).getMetadataEvidence().characterLength().value());
        assertEquals(10, columns.get(5).getMetadataEvidence().numericPrecision().value());
        assertEquals(2, columns.get(5).getMetadataEvidence().numericScale().value());
        assertEquals(MetadataValue.State.ABSENT, columns.get(5).getMetadataEvidence().characterLength().state());
        assertEquals(10, columns.get(0).getMetadataEvidence().numericPrecision().value());
        assertEquals(0, columns.get(0).getMetadataEvidence().numericScale().value());
        var mapper = new HiveLogicalTypeMapper();
        for (ColumnDescription column : columns)
            assertEquals(TypeFidelity.EXACT, mapper.toLogical(column).fidelity(), column.getFieldName());
    }

    @Test void tablePrefixMatchIgnoresStoredCase() throws Exception {
        var executed = new ArrayList<String>();
        Connection connection = connection(executed, formatted("EXTERNAL_TABLE", TEXT_INPUT, LAZY_SERDE),
                properties(),
                metadata(jdbc("plain.c_int", Types.INTEGER, "int", "java.lang.Integer", 10, 0, true)),
                describe(row("c_int", "int", null)));
        assertEquals(List.of("c_int"), HiveCatalogColumns.read(connection, "s", "Plain", 5).stream()
                .map(ColumnDescription::getFieldName).toList());
        assertEquals("DESCRIBE FORMATTED `s`.`Plain`", executed.get(1));
    }

    @Test void rejectsLengthOutsideTheVerifiedCharacterRange() throws Exception {
        SQLException tooWide = assertThrows(SQLException.class, () -> readType("char(256)", Types.CHAR, 256));
        assertTrue(tooWide.getMessage().contains("1..255"));
        SQLException zero = assertThrows(SQLException.class, () -> readType("char(0)", Types.CHAR, 0));
        assertTrue(zero.getMessage().contains("1..255"));
        SQLException varchar = assertThrows(SQLException.class, () -> readType("varchar(65536)", Types.VARCHAR, 65536));
        assertTrue(varchar.getMessage().contains("1..65535"));
        SQLException varcharZero = assertThrows(SQLException.class, () -> readType("varchar(0)", Types.VARCHAR, 0));
        assertTrue(varcharZero.getMessage().contains("1..65535"));
    }

    @Test void rejectsJdbcLengthThatDisagreesWithTheTypeString() {
        SQLException failure = assertThrows(SQLException.class, () -> readType("varchar(5)", Types.VARCHAR, 4));
        assertTrue(failure.getMessage().contains("disagrees"));
    }

    @Test void rejectsUnalignedAndPartialColumnSets() {
        assertThrows(SQLException.class, () -> readNamed("other.c_int", "c_int"));
        assertThrows(SQLException.class, () -> readNamed("plain.c_int", "c_other"));
    }

    @Test void rejectsViewsPartitionsAcidAndNonTextStorage() {
        assertThrows(SQLFeatureNotSupportedException.class, () -> readShape("VIRTUAL_VIEW", TEXT_INPUT, "null", false, false));
        assertThrows(SQLFeatureNotSupportedException.class, () -> readShape("EXTERNAL_TABLE", TEXT_INPUT, LAZY_SERDE, true, false));
        assertThrows(SQLFeatureNotSupportedException.class, () -> readShape("MANAGED_TABLE", TEXT_INPUT, LAZY_SERDE, false, true));
        SQLFeatureNotSupportedException orc = assertThrows(SQLFeatureNotSupportedException.class,
                () -> readShape("MANAGED_TABLE", "org.apache.hadoop.hive.ql.io.orc.OrcInputFormat", LAZY_SERDE, false, false));
        assertTrue(orc.getMessage().contains("TEXTFILE"));
        SQLFeatureNotSupportedException managed = assertThrows(SQLFeatureNotSupportedException.class,
                () -> readShape("MANAGED_TABLE", TEXT_INPUT, LAZY_SERDE, false, false));
        assertTrue(managed.getMessage().contains("TEXTFILE"));
        SQLFeatureNotSupportedException formattedAcid = assertThrows(SQLFeatureNotSupportedException.class, () -> {
            var rows = new ArrayList<>(List.of(formattedRows("EXTERNAL_TABLE", TEXT_INPUT, LAZY_SERDE)));
            rows.add(row("", "transactional", "true"));
            HiveCatalogColumns.read(connection(new ArrayList<>(), grid(3, rows.toArray(String[][]::new)), properties(),
                    metadata(jdbc("plain.c_int", Types.INTEGER, "int", "java.lang.Integer", 10, 0, true)),
                    describe(row("c_int", "int", null))), "s", "plain", 5);
        });
        assertTrue(formattedAcid.getMessage().contains("ACID"));
        assertThrows(SQLFeatureNotSupportedException.class, () -> readVersion("4.1.0"));
    }

    @Test void paddedPartitionMarkerIsRejected() {
        SQLFeatureNotSupportedException failure = assertThrows(SQLFeatureNotSupportedException.class, () -> {
            var executed = new ArrayList<String>();
            var rows = new ArrayList<>(List.of(formattedRows("EXTERNAL_TABLE", TEXT_INPUT, LAZY_SERDE)));
            rows.add(2, row("", "", ""));
            rows.add(3, row("# Partition Information   ", " ", null));
            rows.add(4, row("# col_name", "data_type", "comment"));
            rows.add(5, row("dt", "string", null));
            HiveCatalogColumns.read(connection(executed, grid(3, rows.toArray(String[][]::new)), properties(),
                    metadata(jdbc("plain.c_int", Types.INTEGER, "int", "java.lang.Integer", 10, 0, true)),
                    describe(row("c_int", "int", null))), "s", "plain", 5);
        });
        assertTrue(failure.getMessage().contains("partitioned"));
    }

    private static void readType(String nativeType, int jdbcType, int precision) throws SQLException {
        String className = jdbcType == Types.CHAR || jdbcType == Types.VARCHAR ? "java.lang.String" : "java.lang.Object";
        HiveCatalogColumns.read(connection(new ArrayList<>(), formatted("EXTERNAL_TABLE", TEXT_INPUT, LAZY_SERDE), properties(),
                metadata(jdbc("plain.c", jdbcType, nativeType, className, precision, 0, false)),
                describe(row("c", nativeType, null))), "s", "plain", 5);
    }

    private static void readNamed(String jdbcName, String describedName) throws SQLException {
        HiveCatalogColumns.read(connection(new ArrayList<>(), formatted("EXTERNAL_TABLE", TEXT_INPUT, LAZY_SERDE), properties(),
                metadata(jdbc(jdbcName, Types.INTEGER, "int", "java.lang.Integer", 10, 0, true)),
                describe(row(describedName, "int", null))), "s", "plain", 5);
    }

    private static void readShape(String tableType, String input, String serde, boolean partitioned, boolean acid) throws SQLException {
        ResultSet formatted = formatted(tableType, input, serde);
        if (partitioned) {
            var rows = new ArrayList<>(List.of(formattedRows(tableType, input, serde)));
            rows.add(1, row("# Partition Information", "", ""));
            rows.add(2, row("dt", "string", null));
            formatted = grid(3, rows.toArray(String[][]::new));
        }
        ResultSet props = acid ? properties("transactional", "true") : properties();
        HiveCatalogColumns.read(connection(new ArrayList<>(), formatted, props,
                metadata(jdbc("plain.c_int", Types.INTEGER, "int", "java.lang.Integer", 10, 0, true)),
                describe(row("c_int", "int", null))), "s", "plain", 5);
    }

    private static void readVersion(String version) throws SQLException {
        var executed = new ArrayList<String>();
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenAnswer(invocation -> {
            executed.add(invocation.getArgument(0));
            return grid(1, row(version));
        });
        HiveCatalogColumns.read(connection, "s", "plain", 5);
    }

    private static Connection connection(List<String> executed, ResultSet formatted, ResultSet props,
            ResultSetMetaData metadata, ResultSet described) throws SQLException {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            executed.add(sql);
            if (sql.equals("SELECT version()")) return grid(1, row(HiveCatalogColumns.VERSION));
            if (sql.startsWith("DESCRIBE FORMATTED ")) return formatted;
            if (sql.startsWith("SHOW TBLPROPERTIES ")) return props;
            if (sql.startsWith("SELECT * FROM ") && sql.endsWith(" WHERE 1=0")) return jdbcRows(metadata);
            if (sql.startsWith("DESCRIBE ")) return described;
            throw new AssertionError(sql);
        });
        return connection;
    }

    private static ResultSet formatted(String tableType, String input, String serde) throws SQLException {
        return grid(3, formattedRows(tableType, input, serde));
    }

    private static String[][] formattedRows(String tableType, String input, String serde) {
        return new String[][] {
                row("c_int", "int                  ", null),
                row("   ", null, null),
                row("# Detailed Table Information", "", ""),
                row("Table Type:", tableType + "   ", null),
                row("# Storage Information", "", ""),
                row("SerDe Library:", serde, null),
                row("InputFormat:", "  " + input, null)};
    }

    private static ResultSet properties(String... pair) throws SQLException {
        if (pair.length == 0) return grid(2);
        return grid(2, row(pair[0], pair[1]));
    }

    private static ResultSet describe(String[]... rows) throws SQLException { return grid(3, rows); }

    private static ResultSet grid(int columns, String[]... rows) throws SQLException {
        ResultSet result = mock(ResultSet.class);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(metadata.getColumnCount()).thenReturn(columns);
        when(result.getMetaData()).thenReturn(metadata);
        int[] cursor = {-1};
        when(result.next()).thenAnswer(invocation -> ++cursor[0] < rows.length);
        when(result.getString(anyInt())).thenAnswer(invocation -> {
            int column = invocation.getArgument(0);
            String[] row = rows[cursor[0]];
            return column - 1 < row.length ? row[column - 1] : null;
        });
        return result;
    }

    private static ResultSet jdbcRows(ResultSetMetaData metadata) throws SQLException {
        ResultSet result = mock(ResultSet.class);
        when(result.getMetaData()).thenReturn(metadata);
        when(result.next()).thenReturn(false);
        return result;
    }

    private static ResultSetMetaData metadata(Jdbc... columns) throws SQLException {
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(metadata.getColumnCount()).thenReturn(columns.length);
        for (int i = 0; i < columns.length; i++) {
            int n = i + 1;
            Jdbc column = columns[i];
            when(metadata.getColumnName(n)).thenReturn(column.name);
            when(metadata.getColumnType(n)).thenReturn(column.type);
            when(metadata.getColumnTypeName(n)).thenReturn(column.typeName);
            when(metadata.getColumnClassName(n)).thenReturn(column.className);
            when(metadata.getColumnDisplaySize(n)).thenReturn(column.precision);
            when(metadata.getPrecision(n)).thenReturn(column.precision);
            when(metadata.getScale(n)).thenReturn(column.scale);
            when(metadata.isSigned(n)).thenReturn(column.signed);
            when(metadata.isNullable(n)).thenReturn(ResultSetMetaData.columnNullable);
            when(metadata.isAutoIncrement(n)).thenReturn(false);
        }
        return metadata;
    }

    private static Jdbc jdbc(String name, int type, String typeName, String className, int precision, int scale, boolean signed) {
        return new Jdbc(name, type, typeName, className, precision, scale, signed);
    }

    private static String[] row(String... cells) { return cells; }

    private record Jdbc(String name, int type, String typeName, String className, int precision, int scale, boolean signed) { }
}
