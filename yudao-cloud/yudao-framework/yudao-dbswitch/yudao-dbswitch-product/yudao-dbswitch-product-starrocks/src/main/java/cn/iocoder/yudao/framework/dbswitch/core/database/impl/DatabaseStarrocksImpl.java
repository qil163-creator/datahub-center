package cn.iocoder.yudao.framework.dbswitch.core.database.impl;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.IDatabaseInterface;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedIntegerKeyProjection;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedKeyComponentProjection;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedTextKeyProjection;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.core.util.StarrocksRelationIdentity;
import cn.iocoder.yudao.framework.dbswitch.product.starrocks.StarrocksAddColumn;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * StarRocks元信息实现。
 */
public class DatabaseStarrocksImpl extends DatabaseMysqlCompatibleOlapImpl implements IDatabaseInterface {

    /** 原生会话临时表必须位于 default_catalog；使用现有 PRIMARY KEY 方言，不继承 Doris MoW 属性。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.TemporaryTableDiagnosticPlan temporaryTableDiagnosticPlan(
            String randomName) throws SQLException {
        if (randomName == null || !randomName.matches("dbswitch_diag_[a-f0-9]{32}")) {
            throw new IllegalArgumentException("诊断表必须使用组件生成的随机名称");
        }
        String relation = "`" + randomName + "`";
        StringBuilder create = new StringBuilder("CREATE TEMPORARY TABLE " + relation
                + " (`probe_id` BIGINT NOT NULL,`probe_value` VARCHAR(96) NULL) ENGINE=OLAP\n");
        postAppendCreateTableSql(create, null, List.of("probe_id"), Map.of(), "probe_id");
        // StarRocks 官方临时表契约明确不支持 ALTER TABLE，因此不借用普通表 ALTER/RENAME。
        return new cn.iocoder.yudao.framework.dbswitch.core.model.TemporaryTableDiagnosticPlan(
                create.toString(), relation, null, "DROP TEMPORARY TABLE IF EXISTS " + relation,
                "INSERT INTO " + relation + " (probe_id,probe_value) VALUES (4,'upserted')",
                null, Integer.MAX_VALUE, "TRUNCATE TABLE " + relation, null, null, null,
                null, null, false, true);
    }

    /** 只接受当前原生表能力实现对应的 3.5 系列，不能以 MySQL 协议版本推断临时表支持。 */
    @Override
    public void validateTemporaryTableDiagnosticConnection(Connection connection, int timeoutSeconds) throws SQLException {
        if (timeoutSeconds < 1 || timeoutSeconds > 60) {
            throw new IllegalArgumentException("诊断超时必须在 1 到 60 秒之间");
        }
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeoutSeconds);
            statement.setMaxRows(65);
            try (var rows = statement.executeQuery("SHOW FRONTENDS")) {
                int count = 0;
                while (rows.next()) {
                    String version = rows.getString("Version");
                    if (++count > 64 || version == null || !version.matches("3\\.5\\.[0-9]+(?:[-+].*)?")) {
                        throw new java.sql.SQLFeatureNotSupportedException("此 StarRocks 原生诊断限定 3.5 系列", "0A000");
                    }
                }
                if (count == 0) {
                    throw new SQLException("StarRocks FE 未返回产品版本");
                }
            }
        }
    }

    /**
     * 初始化 {@code DatabaseStarrocksImpl}及其父类状态。
     */
    public DatabaseStarrocksImpl() {
        super(ProductTypeEnum.STARROCKS.getDriveClassName());
    }

    /**
     * 读取当前关系的结构指纹，供后续一致性比较。
     */
    @Override
    public String queryRelationFingerprint(Connection connection, String reference) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.STARROCKS) {
            return super.queryRelationFingerprint(connection, reference);
        }
        return StarrocksRelationIdentity.fingerprint(connection, reference);
    }

    /**
     * 单个物理列转为保留 NULL 的文本表达式；不接受任意 SQL，不代表可作为无损身份编码。
     */
    @Override
    public String textColumnExpression(String column) {
        if (getDatabaseType() != ProductTypeEnum.STARROCKS) {
            return super.textColumnExpression(column);
        }
        return olapTextColumnExpression(column);
    }

    /**
     * 有界键投影的方言扩展点；两个参数都是物理标识符，不接受 SQL 表达式。
     */
    @Override
    public BoundedIntegerKeyProjection integerKeyProjection(String tableAlias, String column) {
        if (getDatabaseType() != ProductTypeEnum.STARROCKS) {
            return super.integerKeyProjection(tableAlias, column);
        }
        return olapIntegerComponent(ProductTypeEnum.STARROCKS, tableAlias, column, 21, true);
    }

    /**
     * 复合身份分量；原生类型及编码范围由产品实现证明，不从文本转换结果猜类型。
     */
    @Override
    public BoundedKeyComponentProjection keyComponentProjection(String tableAlias, String column, int maxBytes) {
        if (getDatabaseType() != ProductTypeEnum.STARROCKS) {
            return super.keyComponentProjection(tableAlias, column, maxBytes);
        }
        if (maxBytes <= 0 || maxBytes > 256) {
            throw new IllegalArgumentException("Invalid key byte bound");
        }
        return olapIntegerComponent(ProductTypeEnum.STARROCKS, tableAlias, column, Math.min(maxBytes, 21), true);
    }

    /**
     * 获取数据库类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return ProductTypeEnum.STARROCKS;
    }

    /** 仅接受物理名称。可空值列，不使用主键模型、聚合、默认值或生成列。 */
    @Override
    public String getAddColumnSql(String targetSchemaName, String targetTableName, ColumnDescription targetColumn) {
        return StarrocksAddColumn.sql(this, targetSchemaName, targetTableName, targetColumn);
    }

    /**
     * 获取指定模式表的元信息。
     */
    @Override
    public List<cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription> queryTableColumnMeta(
            Connection connection, String schema, String table) {
        return enrichNativeValueTypes(connection, schema, table, super.queryTableColumnMeta(connection, schema, table));
    }

    /**
     * 读取表结构中的列定义。
     */
    @Override
    public List<cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription> queryTableColumnMetaOnly(
            Connection connection, String schema, String table) {
        return enrichNativeValueTypes(connection, schema, table,
                super.queryTableColumnMetaOnly(connection, schema, table));
    }

    /**
     * 补充当前列的原生值类型及驱动事实。
     */
    private List<cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription> enrichNativeValueTypes(
            Connection connection,
            String schema, String table,
            List<cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription> columns) {
        try (var p = connection.prepareStatement(
                "SELECT COLUMN_NAME,DATA_TYPE,COLUMN_TYPE,NUMERIC_PRECISION,NUMERIC_SCALE FROM information_schema.columns WHERE TABLE_SCHEMA=? AND TABLE_NAME=?")) {
            p.setQueryTimeout(30);
            p.setString(1, schema);
            p.setString(2, table);
            var byName = columns.stream().collect(java.util.stream.Collectors.toMap(c -> c.getFieldName(), c -> c));
            var seen = new java.util.HashSet<String>();
            try (var r = p.executeQuery()) {
                while (r.next()) {
                    String name = r.getString(1), type = r.getString(2);
                    var column = byName.get(name);
                    if (column == null || !seen.add(name)) {
                        throw new SQLException("StarRocks column identity differs from native catalog");
                    }
                    if ("varbinary".equalsIgnoreCase(type)) {
                        column.setFieldType(java.sql.Types.VARBINARY);
                        column.setFieldTypeName("VARBINARY");
                    } else if ("decimal".equalsIgnoreCase(type)) {
                        int precision = r.getInt(4), scale = r.getInt(5);
                        if (!r.getString(3).replace(" ", "").equalsIgnoreCase(
                                "decimal(" + precision + "," + scale + ")") || precision < 1 || precision > 38 || scale < 0 || scale > precision) {
                            throw new SQLException("StarRocks decimal evidence differs");
                        }
                        column.setFieldType(java.sql.Types.DECIMAL);
                        column.setFieldTypeName("DECIMAL");
                        column.setPrecisionSize(precision);
                        column.setScaleSize(scale);
                    }
                }
            }
            if (seen.size() != columns.size()) {
                throw new SQLException("StarRocks native column set incomplete");
            }
            return columns;
        } catch (Exception failure) {
            throw new IllegalStateException("StarRocks native value metadata could not be confirmed");
        }
    }

    /**
     * 精确文本键投影；alias 可为 null（未限定列），其余输入均须为物理标识符。
     */
    @Override
    public BoundedTextKeyProjection textKeyProjection(String alias, String column, int maxBytes) {
        if (getDatabaseType() != ProductTypeEnum.STARROCKS) {
            return super.textKeyProjection(alias, column, maxBytes);
        }
        return textProjection(alias, column, maxBytes, false);
    }

    /**
     * 生成文本或整数键分量的查询投影。
     */
    @Override
    public BoundedKeyComponentProjection textAndIntegerKeyComponentProjection(Connection connection,
            String alias, String column, int maxBytes) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.STARROCKS) {
            return super.textAndIntegerKeyComponentProjection(connection, alias, column, maxBytes);
        }
        return textProjection(alias, column, maxBytes, true);
    }

    /**
     * 根据当前输入构建并返回 {@code NativeTextProjection}。
     */
    private static NativeTextProjection textProjection(String alias, String column, int maxBytes, boolean mixed) {
        if (maxBytes < 1 || maxBytes > 4096) {
            throw new IllegalArgumentException("Invalid key byte bound");
        }
        String field = (alias == null ? "" : RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.STARROCKS,
                alias) + ".")
                + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.STARROCKS, column);
        String text = "CAST(" + field + " AS STRING)";
        String value = "CASE WHEN length(" + text + ") <= " + maxBytes
                + " THEN CAST(substring(" + text + ",1," + maxBytes + ") AS VARBINARY) ELSE NULL END";
        // MySQL 传输协议也将 CHAR 报告为 VARCHAR。TYPEOF 检查未转换的源值。
        String type = "CAST(CASE typeof(" + field + ") WHEN 'varchar' THEN 12"
                + (mixed ? " WHEN 'smallint' THEN 5 WHEN 'int' THEN 4 WHEN 'bigint' THEN -5" : "") + " ELSE 0 END AS INT)";
        return new NativeTextProjection("hex(" + text + ")", "hex(" + value + ")", value, type, maxBytes, mixed);
    }

    private record NativeTextProjection(String identitySql, String groupSql, String valueSql, String typeSql,
                                        int maxBytes,
                                        boolean mixed) implements BoundedTextKeyProjection, BoundedKeyComponentProjection {

        /**
         * 某些原生传输协议会丢失类型；可改为启用当前行提供的有界验证信息。
         */
        @Override
        public boolean requiresRowTypeWitness() {
            return true;
        }

        /**
         * 说明当前查询路径是否要求显式添加外层数量限制。
         */
        @Override
        public boolean requiresExplicitOuterLimit() {
            return true;
        }

        /**
         * 0 表示类型未经验证；不可由转成文本的结果反推源字段类型。
         */
        @Override
        public int jdbcType(java.sql.ResultSetMetaData metadata, int column) {
            return 0;
        }

        /**
         * 当前行提供的有界原生验证信息，不持有游标或连接。
         */
        @Override
        public int jdbcType(java.sql.ResultSet rows, int column) throws SQLException {
            if (rows.getMetaData().getColumnType(column) != java.sql.Types.INTEGER) {
                return 0;
            }
            int type = rows.getInt(column);
            return !rows.wasNull() && (type == java.sql.Types.VARCHAR || mixed && (type == java.sql.Types.SMALLINT
                    || type == java.sql.Types.INTEGER || type == java.sql.Types.BIGINT)) ? type : 0;
        }

        /**
         * 有界的精确值过滤表达式，返回方言安全常量，不依赖会话反斜杠转义或 collation。
         */
        @Override
        public String equalitySql(String value) {
            if (value == null) {
                throw new IllegalArgumentException("Non-null text key required");
            }
            byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (bytes.length > maxBytes || !value.equals(new String(bytes, java.nio.charset.StandardCharsets.UTF_8))) {
                throw new IllegalArgumentException("Invalid or oversized UTF-8 text key");
            }
            return identitySql + " = '" + java.util.HexFormat.of().withUpperCase().formatHex(bytes) + "'";
        }
    }

    /**
     * 返回布尔类型。
     */
    @Override
    protected String getBooleanType() {
        return "BOOLEAN";
    }

    /**
     * 将当前键列定义标记为非空。
     */
    @Override
    protected boolean forceKeyColumnNotNull(List<String> pks, String fieldname) {
        return pks != null && pks.contains(fieldname);
    }

    /**
     * 返回表键类型。
     */
    @Override
    protected String getTableKeyType(List<String> primaryKeys, Map<String, String> tblProperties) {
        if (!primaryKeys.isEmpty() && !getBooleanProperty(tblProperties, "starrocks.disablePrimaryKey")) {
            return "PRIMARY KEY";
        }
        return super.getTableKeyType(primaryKeys, tblProperties);
    }

    /**
     * 返回副本数量。
     */
    @Override
    protected String getReplicationNum(Map<String, String> tblProperties) {
        if (tblProperties == null) {
            return "1";
        }
        String starrocksReplicationNum = tblProperties.get("starrocks.replicationNum");
        return starrocksReplicationNum == null || starrocksReplicationNum.trim().isEmpty()
                ? super.getReplicationNum(tblProperties)
                : starrocksReplicationNum;
    }
}
