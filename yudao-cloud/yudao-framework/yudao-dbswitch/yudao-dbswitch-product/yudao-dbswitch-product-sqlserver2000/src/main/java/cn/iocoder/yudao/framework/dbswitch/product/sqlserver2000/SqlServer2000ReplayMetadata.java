package cn.iocoder.yudao.framework.dbswitch.product.sqlserver2000;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.*;
import cn.iocoder.yudao.framework.dbswitch.core.schema.SchemaFingerprint;
import cn.iocoder.yudao.framework.dbswitch.core.schema.nativecatalog.SqlServer2000NativeCatalog;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.LockedReplayColumn;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;

import java.sql.*;
import java.util.*;

/** 仅使用原版 8.00.760 目录及锁，不声明支持现代 sys.* 视图、MERGE 或快照隔离。 */
public final class SqlServer2000ReplayMetadata {

    /**
     * 初始化 {@code SqlServer2000ReplayMetadata}。
     */
    private SqlServer2000ReplayMetadata() {
    }

    public record Shape(TableSchemaMetadata schema, Map<String, LockedReplayColumn> columns, String physical,
                        String fingerprint) {

    }

    /**
     * 返回当前操作使用的数据库产品。
     */
    public static void product(Connection c) throws SQLException {
        if (!"Microsoft SQL Server".equals(c.getMetaData().getDatabaseProductName())
                || !SqlServer2000NativeCatalog.SERVER_VERSION.equals(c.getMetaData().getDatabaseProductVersion())) {
            throw new SQLException("SQL Server 2000 replay requires original 8.00.760");
        }
    }

    /**
     * 按SQL Server 2000方言转义并引用标识符。
     */
    public static String quote(String name) {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) {
            throw new IllegalArgumentException("SQL Server 2000 replay requires ordinary physical identifiers");
        }
        return "[" + name + "]";
    }

    /**
     * 生成模式与表组成的限定关系名。
     */
    public static String relation(String schema, String table) {
        return quote(schema) + '.' + quote(table);
    }

    /**
     * 获取当前操作所需的数据库或资源锁。
     */
    public static void lock(Connection c, String relation, boolean writing, int timeout) throws SQLException {
        if (c.getAutoCommit()) {
            throw new SQLException("SQL Server 2000 table lock requires an owned transaction");
        }
        try (var p = c.prepareStatement(
                "SELECT TOP 1 1 FROM " + relation + " WITH(" + (writing ? "TABLOCKX" : "TABLOCK") + ",HOLDLOCK)")) {
            p.setQueryTimeout(timeout);
            try (var rows = p.executeQuery()) {
                while (rows.next()) { /* 耗尽加锁语句的结果。 */ }
            }
        }
    }

    /**
     * 返回物理。
     */
    public static String physical(Connection c, String schema, String table, int timeout) throws SQLException {
        product(c);
        quote(schema);
        quote(table);
        try (var p = c.prepareStatement(
                "SELECT DB_ID(),DB_NAME(),CAST(SERVERPROPERTY('Collation') AS VARCHAR(128)),o.id,o.crdate "
                        + "FROM sysobjects o JOIN sysusers u ON u.uid=o.uid WHERE o.xtype='U' AND u.name=? AND o.name=?")) {
            p.setQueryTimeout(timeout);
            p.setString(1, schema);
            p.setString(2, table);
            try (var r = p.executeQuery()) {
                if (!r.next()) {
                    throw new SQLException("SQL Server 2000 physical table is missing");
                }
                var values = new ArrayList<String>();
                for (int i = 1; i <= 5; i++) {
                    String value = r.getString(i);
                    if (value == null) {
                        throw new SQLException("SQL Server 2000 physical identity is incomplete");
                    }
                    values.add(value);
                }
                if (r.next()) {
                    throw new SQLException("SQL Server 2000 physical identity is ambiguous");
                }
                // crdate 仅检测对象替换；外部保留的代次用于保护恢复和 TRUNCATE。
                return DigestUtil.sha256Hex("SQL2000_PHYSICAL_V1\0" + values);
            }
        }
    }

    /**
     * 使用 SQL Server 2000 系统目录读取物理表身份与精确重放结构。
     */
    public static Shape read(Connection c, String schema, String table, int timeout) throws SQLException {
        product(c);
        String physical = physical(c, schema, table, timeout);
        var metadata = SqlServer2000NativeCatalog.readForReplay(c, schema, table, timeout);
        var columns = new LinkedHashMap<String, LockedReplayColumn>();
        for (var column : metadata.columns()) {
            var e = column.getMetadataEvidence();
            String type = e.nativeType().value();
            if (e.defaultExpression().state() != MetadataValue.State.ABSENT || column.isAutoIncrement()) {
                throw new SQLException("SQL Server 2000 replay does not admit defaults or identity columns");
            }
            CursorKeyContract.Domain domain = null;
            int jdbc, length = 0;
            if ("int".equals(type)) {
                jdbc = Types.INTEGER;
                domain = CursorKeyContract.Domain.integer(32, false);
            } else if (type != null && type.startsWith("decimal(")) {
                jdbc = Types.DECIMAL;
                domain = CursorKeyContract.Domain.decimal(e.numericPrecision().value(), e.numericScale().value(),
                        false);
            } else if (type != null && type.matches("n?varchar\\([1-9][0-9]*\\)")) {
                jdbc = type.startsWith("n") ? Types.NVARCHAR : Types.VARCHAR;
                length = Math.toIntExact(e.characterLength().value());
            } else {
                throw new SQLException("SQL Server 2000 native scalar is outside the replay profile");
            }
            columns.put(column.getFieldName(),
                    new LockedReplayColumn(type, jdbc, column.isNullable(), domain, length, jdbc == Types.VARCHAR));
        }
        if (!physical.equals(physical(c, schema, table, timeout))) {
            throw new SQLException("SQL Server 2000 physical table changed");
        }
        return new Shape(metadata, Collections.unmodifiableMap(columns), physical, SchemaFingerprint.of(metadata));
    }

    /**
     * 返回键集合。
     */
    public static CursorKeyContract keys(Shape shape, List<String> keys) throws SQLException {
        if (!shape.schema().primaryKeys().equals(keys)) {
            throw new SQLException("SQL Server 2000 replay requires the exact ordered primary key");
        }
        var domains = new ArrayList<CursorKeyContract.Domain>();
        var facts = new ArrayList<String>();
        for (String name : keys) {
            var column = shape.columns().get(name);
            if (column == null) {
                throw new SQLException("SQL Server 2000 key is missing");
            }
            column.requireOrdered();
            domains.add(column.domain());
            facts.add(column.nativeType());
        }
        return new CursorKeyContract(ProductTypeEnum.SQLSERVER2000, DigestUtil.sha256Hex("SQL2000_KEYS_V1\0" + facts),
                domains);
    }
}
