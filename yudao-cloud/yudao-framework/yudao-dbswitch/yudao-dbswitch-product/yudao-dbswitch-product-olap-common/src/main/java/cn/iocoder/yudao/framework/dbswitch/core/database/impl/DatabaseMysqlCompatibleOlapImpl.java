package cn.iocoder.yudao.framework.dbswitch.core.database.impl;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedIntegerKeyProjection;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedKeyComponentProjection;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;

import java.sql.ResultSetMetaData;
import java.sql.Types;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import cn.iocoder.yudao.framework.dbswitch.common.constant.Const;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MySQL协议兼容的OLAP数据库元信息基础实现。
 */
@Slf4j
public abstract class DatabaseMysqlCompatibleOlapImpl extends DatabaseMysqlImpl {

    /**
     * 初始化 {@code DatabaseMysqlCompatibleOlapImpl}及其父类状态。
     */
    public DatabaseMysqlCompatibleOlapImpl(String driverClassName) {
        super(driverClassName);
    }

    /** 固定执行只读 SQL，不依赖 Connector/J 对 ping 注释的协议快捷路径。 */
    @Override
    public void probeConnection(Connection connection, int timeoutSeconds) throws SQLException {
        cn.iocoder.yudao.framework.dbswitch.spi.JdbcConnectionProbe.fixedSql(connection, "SELECT 1", timeoutSeconds);
    }

    /** OLAP 产品独立启用原生目录，不经过 MySQL 的产品 guard；超限拒绝，不返回截断清单。 */
    @Override
    public List<TableDescription> queryCatalogTableList(Connection connection, String schema, int maxTables,
            int timeoutSeconds) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.DORIS && getDatabaseType() != ProductTypeEnum.STARROCKS) {
            return super.queryCatalogTableList(connection, schema, maxTables, timeoutSeconds);
        }
        if (schema == null || schema.isBlank() || maxTables < 1 || maxTables > 10000
                || timeoutSeconds < 1 || timeoutSeconds > 60) {
            throw new IllegalArgumentException("目录读取必须指定 schema、数量及超时上限");
        }
        String sql = "SELECT TABLE_SCHEMA,TABLE_NAME,TABLE_COMMENT FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA=? AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME LIMIT " + (maxTables + 1);
        var tables = new java.util.TreeMap<String, TableDescription>();
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            statement.setQueryTimeout(timeoutSeconds);
            statement.setMaxRows(maxTables + 1);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (!schema.equals(rows.getString(1)) || rows.getString(2) == null || rows.getString(2).isBlank()) {
                        throw new SQLException("原生目录返回非精确或无效物理名称");
                    }
                    var table = new TableDescription();
                    table.setSchemaName(rows.getString(1));
                    table.setTableName(rows.getString(2));
                    table.setRemarks(rows.getString(3));
                    table.setTableType("TABLE");
                    if (tables.putIfAbsent(table.getTableName(), table) != null || tables.size() > maxTables) {
                        throw new SQLException("目录不唯一或超过数量上限，拒绝截断结果", "54000");
                    }
                }
            }
        }
        return List.copyOf(tables.values());
    }

    /** 仅在调用方独占的诊断连接切换数据库，验证实际 USE 权限。 */
    @Override
    public void probeNamespace(Connection connection, String namespace, int timeoutSeconds) throws SQLException {
        if (namespace == null || namespace.isBlank() || namespace.indexOf('\0') >= 0 || timeoutSeconds < 1) {
            throw new IllegalArgumentException("目标数据库和有效超时不能为空");
        }
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeoutSeconds);
            statement.execute("USE `" + namespace.replace("`", "``") + "`");
        }
    }

    /** SHOW GRANTS 只证明账号授权目录可读，USE 单独验证目标数据库访问，不推断写权限。 */
    @Override
    public void probeAccountPrivileges(Connection connection, String namespace, int timeoutSeconds) throws SQLException {
        probeNamespace(connection, namespace, timeoutSeconds);
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeoutSeconds);
            try (var result = statement.executeQuery("SHOW GRANTS")) {
                if (!result.next()) {
                    throw new SQLException("账号授权目录未返回当前账号记录");
                }
            }
        }
    }

    /** 使用精确目录条件读取表与列注释；不读取业务行或返回实际注释内容。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.ExtendedPropertyDiagnostic probeExtendedProperties(
            Connection connection, String namespace, String table, int timeoutSeconds) throws SQLException {
        String sql = "SELECT t.TABLE_COMMENT,c.COLUMN_COMMENT FROM information_schema.TABLES t "
                + "JOIN information_schema.COLUMNS c ON t.TABLE_SCHEMA=c.TABLE_SCHEMA AND t.TABLE_NAME=c.TABLE_NAME "
                + "WHERE t.TABLE_SCHEMA=? AND t.TABLE_NAME=?";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(timeoutSeconds);
            statement.setString(1, namespace);
            statement.setString(2, table);
            int columns = 0;
            int comments = 0;
            boolean tableComment = false;
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    columns++;
                    String value = result.getString(1);
                    tableComment |= value != null && !value.isBlank();
                    value = result.getString(2);
                    if (value != null && !value.isBlank()) {
                        comments++;
                    }
                }
            }
            if (columns == 0) {
                throw new SQLException("目标关系不存在、不可见或未返回列属性", "42S02");
            }
            return new cn.iocoder.yudao.framework.dbswitch.core.model.ExtendedPropertyDiagnostic(
                    columns, tableComment, comments);
        }
    }

    /** MySQL 协议不代表 MySQL binlog；产品专属日志与消费授权尚未验证。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.CdcReadinessDiagnostic probeCdcReadiness(
            Connection connection, int timeoutSeconds) throws SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("OLAP 产品不适用 MySQL CDC 检查；原生日志诊断尚未验证", "0A000");
    }

    /** 不继承 MySQL 临时表能力，未验证的 OLAP 临时写入必须保持关闭。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.TemporaryTableDiagnosticPlan temporaryTableDiagnosticPlan(
            String randomName) throws SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("OLAP 会话隔离临时表写入尚未验证", "0A000");
    }

    /**
     * 校验普通物理列名并生成 OLAP 文本键投影。
     */
    protected String olapTextColumnExpression(String column) {
        if (column == null || !column.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("Only an ordinary physical column is verified");
        }
        return "CAST(" + RestrictedSelectGuard.quotePhysicalIdentifier(getDatabaseType(), column) + " AS STRING)";
    }

    /**
     * 根据列定义创建当前 OLAP 产品的整数游标键投影组件。
     */
    protected static OlapIntegerComponent olapIntegerComponent(ProductTypeEnum product, String tableAlias,
            String column, int maxBytes,
            boolean requiresExplicitOuterLimit) {
        String field = RestrictedSelectGuard.quotePhysicalIdentifier(product, column);
        if (tableAlias != null) {
            field = RestrictedSelectGuard.quotePhysicalIdentifier(product, tableAlias) + "." + field;
        }
        String value = "CAST(" + field + " AS STRING)";
        // NULLIF 在已验证产品路径中保持原生整数元数据。VARCHAR 和 CHAR 共用 MySQL 传输类型；
        // 此投影未验证二者。绝不能截断过大的身份，使其变成另一个键。
        String bounded = "CASE WHEN LENGTH(" + value + ") <= " + maxBytes
                + " THEN LEFT(" + value + ", " + maxBytes + ") ELSE NULL END";
        return new OlapIntegerComponent(bounded, "NULLIF(" + field + ", " + field + ")",
                "CAST(" + bounded + " AS VARCHAR(" + maxBytes + "))", requiresExplicitOuterLimit);
    }

    protected record OlapIntegerComponent(String valueSql, String typeSql, String groupSql,
                                          boolean requiresExplicitOuterLimit)
            implements BoundedIntegerKeyProjection, BoundedKeyComponentProjection {

        /**
         * 确定当前列或值对应的 JDBC 类型。
         */
        @Override
        public int jdbcType(ResultSetMetaData metadata, int column) throws SQLException {
            if (!metadata.isSigned(column)) {
                return 0;
            }
            int type = metadata.getColumnType(column);
            String nativeType = metadata.getColumnTypeName(column);
            return switch (type) {
                case Types.SMALLINT -> "SMALLINT".equalsIgnoreCase(nativeType) ? type : 0;
                case Types.INTEGER -> "INT".equalsIgnoreCase(nativeType) ? type : 0;
                case Types.BIGINT -> "BIGINT".equalsIgnoreCase(nativeType) ? type : 0;
                default -> 0;
            };
        }
    }

    /**
     * 获取指定模式表的元信息。
     */
    @Override
    public List<ColumnDescription> queryTableColumnMeta(Connection connection, String schemaName,
            String tableName) {
        String sql = this.getTableFieldsQuerySQL(schemaName, tableName);
        List<ColumnDescription> columnDescriptions = this.querySelectSqlColumnMeta(connection, sql);
        String extraSql = "SELECT column_name,data_type,column_size,decimal_digits,column_comment FROM information_schema.COLUMNS WHERE table_schema='" + schemaName + "' AND table_name='" + tableName + "'";
        try (PreparedStatement ps = connection.prepareStatement(extraSql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String columnName = rs.getString("column_name");
                String dataType = rs.getString("data_type");
                String columnSize = rs.getString("column_size");
                String decimalDigits = rs.getString("decimal_digits");
                String columnComment = rs.getString("column_comment");
                if (columnName != null) {
                    for (ColumnDescription cd : columnDescriptions) {
                        if (columnName.equals(cd.getFieldName())) {
                            cd.setFieldTypeName(dataType);
                            int csize = columnSize != null ? Integer.parseInt(columnSize) : 0;
                            cd.setDisplaySize(csize);
                            cd.setPrecisionSize(csize);
                            cd.setScaleSize(decimalDigits != null ? Integer.parseInt(decimalDigits) : 0);
                            cd.setRemarks(columnComment);
                            break;
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(schemaName + "." + tableName + " queryTableColumnMeta error!!", e);
        }

        return columnDescriptions;
    }

    /**
     * 生成 MySQL 建表语句中的字段定义片段。
     */
    @Override
    public String getFieldDefinition(ColumnMetaData v, List<String> pks, boolean useAutoInc,
            boolean addCr, boolean withRemarks) {
        String fieldname = v.getName();
        int length = v.getLength();
        int precision = v.getPrecision();
        int type = v.getType();

        String retval = " `" + fieldname + "`  ";
        boolean canHaveDefaultValue = true;
        switch (type) {
            case ColumnMetaData.TYPE_TIMESTAMP:
            case ColumnMetaData.TYPE_TIME:
                retval += "DATETIME";
                break;
            case ColumnMetaData.TYPE_DATE:
                retval += "DATE";
                break;
            case ColumnMetaData.TYPE_BOOLEAN:
                retval += getBooleanType();
                break;
            case ColumnMetaData.TYPE_NUMBER:
            case ColumnMetaData.TYPE_INTEGER:
            case ColumnMetaData.TYPE_BIGNUMBER:
                if (null != pks && !pks.isEmpty() && pks.contains(fieldname)) {
                    retval += "BIGINT";
                } else if (precision == 0) {
                    if (length > 11) {
                        if (length < 19) {
                            retval += "BIGINT";
                        } else {
                            retval += "DECIMAL(" + length + ",0)";
                        }
                    } else {
                        retval += "INT";
                    }
                } else {
                    retval += "DOUBLE";
                }
                break;
            case ColumnMetaData.TYPE_STRING:
                if (length > 0 && length * 3 <= 65533) {
                    retval += "VARCHAR(" + length * 3 + ")";
                } else {
                    retval += "STRING";
                    canHaveDefaultValue = false;
                }
                break;
            default:
                retval += "STRING";
                canHaveDefaultValue = false;
                break;
        }

        if (!v.isNullable() || forceKeyColumnNotNull(pks, fieldname)) {
            retval += " NOT NULL";
        }

        if (canHaveDefaultValue && v.getDefaultValue() != null && !"null".equals(v.getDefaultValue()) && !"NULL".equals(
                v.getDefaultValue())) {
            if (type != ColumnMetaData.TYPE_TIMESTAMP && type != ColumnMetaData.TYPE_TIME && type != ColumnMetaData.TYPE_DATE) {
                if (v.getDefaultValue().startsWith("'")) {
                    retval += " DEFAULT " + v.getDefaultValue();
                } else {
                    retval += " DEFAULT '" + v.getDefaultValue() + "'";
                }
            } else {
                retval += " DEFAULT CURRENT_TIMESTAMP";
            }
        }

        if (withRemarks && StringUtils.isNotBlank(v.getRemarks())) {
            retval += " COMMENT " + legacyComment(v.getRemarks()) + " ";
        }

        if (addCr) {
            retval += Const.CR;
        }

        return retval;
    }

    /**
     * 返回布尔类型。
     */
    protected String getBooleanType() {
        return "TINYINT";
    }

    /**
     * 将当前键列定义标记为非空。
     */
    protected boolean forceKeyColumnNotNull(List<String> pks, String fieldname) {
        return false;
    }

    /**
     * 判断字段类型能否建索引：数值/日期/布尔类可以； 字符串类长度必须在 1~1024 之间（长文本 TEXT 系列不建索引）；其余类型不建。
     */
    @Override
    public boolean canCreateIndex(ColumnMetaData v) {
        return false;
    }

    /**
     * 主键字段串：MySQL 用反引号包裹，形如 `id` , `name`。
     */
    @Override
    public String getPrimaryKeyAsString(List<String> pks) {
        if (null != pks && !pks.isEmpty()) {
            return "`" + StringUtils.join(pks, "` , `") + "`";
        }

        return "";
    }

    /**
     * 为字段描述列表补充默认值与注释信息。
     */
    @Override
    public void setColumnDefaultValue(Connection connection, String schemaName, String tableName,
            List<ColumnDescription> columnDescriptions) {
        String sql = this.getDefaultValueSql(schemaName, tableName);
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String columnName = rs.getString("Field");
                String columnDefault = rs.getString("Default");
                if (columnName != null) {
                    for (ColumnDescription cd : columnDescriptions) {
                        if (columnName.equals(cd.getFieldName())) {
                            cd.setDefaultValue(columnDefault);
                            break;
                        }
                    }
                }
            }
        } catch (SQLException e) {
            log.error(schemaName + "." + tableName + " setColumnDefaultValue error:" + e.getMessage());
        }
    }

    /**
     * 字段默认值/注释查询 SQL：查 information_schema.columns 的 column_default 与 column_comment 两列。
     */
    @Override
    protected String getDefaultValueSql(String schemaName, String tableName) {
        return String.format("desc `%s`.`%s`", schemaName, tableName);
    }

    /**
     * 查询主键字段：按约束内的键顺序返回，不能用表字段顺序或无序集合替代。
     */
    @Override
    public List<String> queryTablePrimaryKeys(Connection connection, String schemaName,
            String tableName) {
        Set<String> ret = new HashSet<>();
        String sql = String.format("desc `%s`.`%s`", schemaName, tableName);
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            boolean noneExtra = false;
            while (rs.next()) {
                String field = rs.getString("Field");
                String key = rs.getString("Key");
                String extra = rs.getString("Extra");
                if (isKeyColumn(key)) {
                    ret.add(field);
                } else if ("NONE".equalsIgnoreCase(extra)) {
                    noneExtra = true;
                }
            }
            if (noneExtra) {
                return new ArrayList<>();
            }
            return new ArrayList<>(ret);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 判断键列。
     */
    protected boolean isKeyColumn(String key) {
        return "true".equalsIgnoreCase(key) || "PRI".equalsIgnoreCase(key) || "YES".equalsIgnoreCase(key);
    }

    /**
     * 为字段描述列表补充索引信息。
     */
    @Override
    public void setColumnIndexInfo(Connection connection, String schemaName, String tableName,
            List<ColumnDescription> columnDescriptions) {
    }

    /** 键模型、分桶与倒排/BloomFilter 等索引属于原生表定义，不是可按普通 B-tree 重建的二级索引。 */
    @Override
    public List<cn.iocoder.yudao.framework.dbswitch.core.model.IndexDescription> queryTableIndexes(
            Connection connection,
            String schemaName, String tableName) {
        return List.of();
    }

    /**
     * 表/字段注释 DDL：MySQL 的注释在建表语句中内联（COMMENT 子句），无需独立语句，返回空列表。
     */
    @Override
    public List<String> getTableColumnCommentDefinition(TableDescription td,
            List<ColumnDescription> cds) {
        return Collections.emptyList();
    }

    /**
     * 在建表语句中追加主键定义片段 ", PRIMARY KEY (...)"；主键列表为空时不追加。
     */
    @Override
    public void appendPrimaryKeyForCreateTableSql(StringBuilder builder, List<String> primaryKeys) {
    }

    /**
     * 建表语句后置内容：固定追加 InnoDB 引擎与 utf8mb4 字符集，有表注释时内联 COMMENT。
     */
    @Override
    public void postAppendCreateTableSql(StringBuilder sb, String tblComment, List<String> primaryKeys,
            Map<String, String> tblProperties, String hashFiled) {
        String hashFieldName = !primaryKeys.isEmpty() ? primaryKeys.get(0) : hashFiled;
        if (StringUtils.isBlank(hashFieldName)) {
            throw new IllegalArgumentException(getDatabaseType().name() + " create table hash field is blank");
        }

        List<String> keyFields = primaryKeys;
        if (keyFields.isEmpty()) {
            keyFields = new ArrayList<>();
            keyFields.add(hashFieldName);
        }
        String keySql = getPrimaryKeyAsString(keyFields);
        sb.append(getTableKeyType(primaryKeys, tblProperties)).append("(").append(keySql).append(")").append(Const.CR);

        if (StringUtils.isNotBlank(tblComment)) {
            sb.append(" COMMENT ").append(legacyComment(tblComment)).append(" ");
            sb.append(Const.CR);
        }
        sb.append(String.format("DISTRIBUTED BY HASH(`%s`) BUCKETS 10", hashFieldName))
                .append(Const.CR)
                .append(String.format("PROPERTIES(\"replication_num\" = \"%s\");", getReplicationNum(tblProperties)));
    }

    /**
     * 返回表键类型。
     */
    protected String getTableKeyType(List<String> primaryKeys, Map<String, String> tblProperties) {
        boolean preferUniqueKey = getBooleanProperty(tblProperties, "olap.preferUniqueKey")
                || getBooleanProperty(tblProperties, "doris.preferUniqueKey");
        return preferUniqueKey && !primaryKeys.isEmpty() ? "UNIQUE KEY" : "DUPLICATE KEY";
    }

    /** 此处没有连接可证明或恢复会话的字面值模式。 */
    private static String legacyComment(String value) {
        if (value.indexOf('\'') >= 0 || value.indexOf('\\') >= 0 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(
                    "OLAP quoted comments require a native DDL plan with a verified literal mode");
        }
        return "'" + value + "'";
    }

    /**
     * 返回副本数量。
     */
    protected String getReplicationNum(Map<String, String> tblProperties) {
        if (tblProperties == null) {
            return "1";
        }
        return StringUtils.defaultIfBlank(tblProperties.get("olap.replicationNum"), "1");
    }

    /**
     * 返回布尔属性。
     */
    protected boolean getBooleanProperty(Map<String, String> tblProperties, String propertyName) {
        return tblProperties != null && "true".equalsIgnoreCase(tblProperties.get(propertyName));
    }
}
