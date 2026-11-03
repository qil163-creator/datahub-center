package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.SchemaTableData;
import cn.iocoder.yudao.framework.dbswitch.core.util.JdbcReadDeadline;
import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.ast.statement.SQLDeleteStatement;
import com.alibaba.druid.sql.ast.statement.SQLInsertStatement;
import com.alibaba.druid.sql.ast.statement.SQLUpdateStatement;
import com.alibaba.druid.sql.visitor.SQLASTVisitorAdapter;

import java.sql.*;
import java.util.*;

/**
 * 针对明确支持的 PostgreSQL/MySQL 产品，不实际执行的 SELECT 计划。
 * 持有短生命周期的只读 JDBC 连接；没有可变或共享的请求状态。
 * 此 API 绝不接受 EXPLAIN ANALYZE，并保留字符串字面量中的分号。
 */
public final class SqlExecutionPlanService {

    @FunctionalInterface
    public interface ConnectionFactory {

        /**
         * 根据当前驱动及连接配置建立数据库连接。
         */
        Connection open() throws SQLException;
    }

    private final ConnectionFactory connections;

    /** 工厂必须从调用方配置的驱动运行时返回一个新建且由本组件持有的连接。 */
    public SqlExecutionPlanService(ConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    /**
     * AST 序列化仅移除语句终止符；字面量中的分号保留。
     */
    public static String planSql(ProductTypeEnum product, String sql) {
        if (sql == null || sql.isBlank() || sql.length() > 100_000) {
            throw new IllegalArgumentException("执行计划 SQL 为空或超过长度限制");
        }
        DbType dialect = dialect(product);
        SqlStatementClassifier.assertReadOnlyQuery(product, sql);
        List<SQLStatement> statements = SQLUtils.parseStatements(sql, dialect);
        if (statements.size() != 1 || !(statements.get(0) instanceof SQLSelectStatement)) {
            throw new IllegalArgumentException("执行计划仅支持单条 SELECT 查询");
        }
        statements.get(0).accept(new SQLASTVisitorAdapter() {
            /**
             * 访问当前 SQL 语法节点并检查其是否符合受限语法约定。
             */
            @Override
            public boolean visit(SQLDeleteStatement x) {
                throw new IllegalArgumentException("禁止修改数据的 CTE");
            }

            /**
             * 访问当前 SQL 语法节点并检查其是否符合受限语法约定。
             */
            @Override
            public boolean visit(SQLInsertStatement x) {
                throw new IllegalArgumentException("禁止修改数据的 CTE");
            }

            /**
             * 访问当前 SQL 语法节点并检查其是否符合受限语法约定。
             */
            @Override
            public boolean visit(SQLUpdateStatement x) {
                throw new IllegalArgumentException("禁止修改数据的 CTE");
            }
        });
        // AST 序列化仅移除语句终止符；字面量中的分号保留。
        String select = SQLUtils.toSQLString(statements.get(0), dialect).strip();
        if (select.endsWith(";")) {
            select = select.substring(0, select.length() - 1);
        }
        return (product == ProductTypeEnum.POSTGRESQL ? "EXPLAIN (FORMAT TEXT)\n" : "EXPLAIN\n") + select;
    }

    /**
     * 解析当前产品对应的数据库方言。
     */
    private static DbType dialect(ProductTypeEnum product) {
        if (product == ProductTypeEnum.POSTGRESQL) {
            return DbType.postgresql;
        }
        if (product == ProductTypeEnum.MYSQL || product == ProductTypeEnum.MARIADB) {
            return DbType.mysql;
        }
        throw new UnsupportedOperationException("数据库产品尚未验证执行计划能力：" + product);
    }

    /**
     * 构建当前规则或执行计划的说明信息。
     */
    public SchemaTableData explain(ProductTypeEnum product, String sql, int maxRows,
            int timeoutSeconds) throws SQLException {
        String plan = planSql(product, sql);
        int rowsLimit = Math.max(1, Math.min(maxRows, 1000));
        int seconds = Math.max(1, Math.min(timeoutSeconds, 120));
        try (Connection connection = connections.open()) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try (JdbcReadDeadline deadline = new JdbcReadDeadline(connection, seconds * 1000);
                 java.sql.Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(seconds);
                statement.setMaxRows(rowsLimit);
                try (ResultSet rs = statement.executeQuery(plan)) {
                    ResultSetMetaData meta = rs.getMetaData();
                    List<String> columns = new ArrayList<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        columns.add(meta.getColumnLabel(i));
                    }
                    List<List<Object>> rows = new ArrayList<>();
                    int characters = 0;
                    while (rs.next()) {
                        deadline.check();
                        List<Object> row = new ArrayList<>();
                        for (int i = 1; i <= columns.size(); i++) {
                            String value = rs.getString(i);
                            if (value != null) {
                                characters += value.length();
                                if (characters > 262_144) {
                                    throw new SQLException("执行计划结果超过大小限制");
                                }
                            }
                            row.add(value);
                        }
                        rows.add(row);
                    }
                    SchemaTableData result = new SchemaTableData();
                    result.setSql(plan);
                    result.setColumns(columns);
                    result.setRows(rows);
                    return result;
                } finally {
                    connection.rollback();
                }
            }
        }
    }
}
