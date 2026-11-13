package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 在线填报-事务型目标库的合并写入。
 *
 * <p>清理旧数据与写入新数据使用同一个连接、同一个事务：任何一步失败都整体回滚，
 * 正式表保持合并前的内容，不会出现“旧数据已删、新数据未写入”的中间状态。</p>
 *
 * <p>只用于支持多语句事务的关系型数据库；分析型、文档型等目标库仍走通用写入器。</p>
 *
 * @author Li
 */
final class FillMergeJdbcWriter {

    /** 支持“删除 + 写入”同事务提交的目标库产品 */
    private static final Set<ProductTypeEnum> TRANSACTIONAL_PRODUCTS = EnumSet.of(
            ProductTypeEnum.MYSQL, ProductTypeEnum.MARIADB, ProductTypeEnum.TIDB, ProductTypeEnum.OCEANBASE,
            ProductTypeEnum.POSTGRESQL, ProductTypeEnum.GREENPLUM, ProductTypeEnum.KINGBASE,
            ProductTypeEnum.HIGHGO, ProductTypeEnum.OPENGAUSS, ProductTypeEnum.GAUSSDB,
            ProductTypeEnum.UXDB, ProductTypeEnum.GBASE8C,
            ProductTypeEnum.ORACLE, ProductTypeEnum.DM, ProductTypeEnum.OSCAR,
            ProductTypeEnum.SQLSERVER, ProductTypeEnum.DB2);

    private static final int BATCH_SIZE = 500;

    private static final int QUERY_TIMEOUT_SECONDS = 300;

    private FillMergeJdbcWriter() {
    }

    /** 目标表定位信息 */
    record Target(ProductTypeEnum product, String schema, String table) {

        String tableRef() {
            return product.quoteSchemaTableName(schema, table);
        }

        String quote(String name) {
            return product.quoteName(name);
        }
    }

    static boolean supports(ProductTypeEnum product) {
        return product != null && TRANSACTIONAL_PRODUCTS.contains(product);
    }

    /**
     * 在一个事务内先删除指定范围的旧数据，再写入全部新数据。
     *
     * @param dataSource 目标库数据源
     * @param target 目标表
     * @param fieldNames 写入列，顺序与每行数据一致
     * @param rows 新数据
     * @param scopeColumns 划定旧数据范围的列
     * @param scopeValues 与范围列一一对应的值
     * @return 写入行数
     */
    static int replaceScope(DataSource dataSource, Target target, List<String> fieldNames, List<Object[]> rows,
            List<String> scopeColumns, List<Object> scopeValues) {
        return inTransaction(dataSource, connection -> {
            String deleteSql = "DELETE FROM " + target.tableRef() + " WHERE " + conditions(target, scopeColumns);
            try (PreparedStatement statement = connection.prepareStatement(deleteSql)) {
                statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                for (int index = 0; index < scopeValues.size(); index++) {
                    statement.setObject(index + 1, scopeValues.get(index));
                }
                statement.executeUpdate();
            }
            insertAll(connection, target, fieldNames, columnTypes(connection, target, fieldNames), rows);
            return rows.size();
        });
    }

    /**
     * 在一个事务内按业务键逐行更新，不存在的行再整批插入。
     *
     * @param dataSource 目标库数据源
     * @param target 目标表
     * @param fieldNames 写入列，顺序与每行数据一致
     * @param rows 新数据
     * @param keyColumns 业务键列（已包含组织与所属期）
     * @return 处理行数
     */
    static int upsert(DataSource dataSource, Target target, List<String> fieldNames, List<Object[]> rows,
            List<String> keyColumns) {
        if (rows.isEmpty()) {
            return 0;
        }
        List<String> valueColumns = fieldNames.stream().filter(name -> !keyColumns.contains(name)).toList();
        if (valueColumns.isEmpty() || !fieldNames.containsAll(keyColumns)) {
            throw new IllegalStateException("业务键配置与写入列不一致，无法按业务键更新");
        }
        return inTransaction(dataSource, connection -> {
            int[] types = columnTypes(connection, target, fieldNames);
            String updateSql = "UPDATE " + target.tableRef() + " SET "
                    + valueColumns.stream().map(name -> target.quote(name) + " = ?").collect(Collectors.joining(", "))
                    + " WHERE " + conditions(target, keyColumns);
            List<Object[]> missing = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
                statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                for (Object[] row : rows) {
                    int parameter = 1;
                    for (String name : valueColumns) {
                        int column = fieldNames.indexOf(name);
                        bind(statement, parameter++, row[column], types[column]);
                    }
                    for (String name : keyColumns) {
                        int column = fieldNames.indexOf(name);
                        bind(statement, parameter++, row[column], types[column]);
                    }
                    if (statement.executeUpdate() == 0) {
                        missing.add(row);
                    }
                }
            }
            insertAll(connection, target, fieldNames, types, missing);
            return rows.size();
        });
    }

    private static void insertAll(Connection connection, Target target, List<String> fieldNames, int[] types,
            List<Object[]> rows) throws SQLException {
        if (rows.isEmpty()) {
            return;
        }
        String insertSql = "INSERT INTO " + target.tableRef() + " ("
                + fieldNames.stream().map(target::quote).collect(Collectors.joining(", "))
                + ") VALUES (" + fieldNames.stream().map(name -> "?").collect(Collectors.joining(", ")) + ")";
        try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            int pending = 0;
            for (Object[] row : rows) {
                for (int column = 0; column < fieldNames.size(); column++) {
                    bind(statement, column + 1, row[column], types[column]);
                }
                statement.addBatch();
                if (++pending == BATCH_SIZE) {
                    statement.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) {
                statement.executeBatch();
            }
        }
    }

    /** 读取目标列的 JDBC 类型，用于空值与日期时间的正确绑定 */
    private static int[] columnTypes(Connection connection, Target target, List<String> fieldNames)
            throws SQLException {
        String sql = "SELECT " + fieldNames.stream().map(target::quote).collect(Collectors.joining(", "))
                + " FROM " + target.tableRef() + " WHERE 1 = 2";
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            try (ResultSet resultSet = statement.executeQuery(sql)) {
                ResultSetMetaData metaData = resultSet.getMetaData();
                int[] types = new int[fieldNames.size()];
                for (int column = 0; column < types.length; column++) {
                    types[column] = metaData.getColumnType(column + 1);
                }
                return types;
            }
        }
    }

    private static String conditions(Target target, List<String> columns) {
        return columns.stream().map(name -> target.quote(name) + " = ?").collect(Collectors.joining(" AND "));
    }

    private static void bind(PreparedStatement statement, int index, Object value, int sqlType)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, sqlType);
        } else if (value instanceof LocalDate date) {
            statement.setDate(index, java.sql.Date.valueOf(date));
        } else if (value instanceof LocalDateTime dateTime) {
            statement.setTimestamp(index, java.sql.Timestamp.valueOf(dateTime));
        } else if (value instanceof LocalTime time) {
            statement.setTime(index, java.sql.Time.valueOf(time));
        } else if (value instanceof BigDecimal decimal) {
            statement.setBigDecimal(index, decimal);
        } else if (value instanceof Integer number) {
            statement.setInt(index, number);
        } else if (value instanceof Long number) {
            statement.setLong(index, number);
        } else if (value instanceof String text) {
            statement.setString(index, text);
        } else {
            statement.setObject(index, value);
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    private static <T> T inTransaction(DataSource dataSource, SqlWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException ex) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    ex.addSuppressed(rollbackFailure);
                }
                throw ex;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("合并写入失败，本次写入未提交：" + rootMessage(ex), ex);
        }
    }

    /** 批量写入异常的外层消息会带上整条语句和参数，这里只取数据库给出的原因 */
    private static String rootMessage(SQLException ex) {
        SQLException root = ex;
        for (int depth = 0; depth < 10 && root.getNextException() != null; depth++) {
            root = root.getNextException();
        }
        return root.getMessage();
    }

}
