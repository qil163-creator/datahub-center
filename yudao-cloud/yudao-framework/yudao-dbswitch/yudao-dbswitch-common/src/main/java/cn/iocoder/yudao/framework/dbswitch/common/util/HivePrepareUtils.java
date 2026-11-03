package cn.iocoder.yudao.framework.dbswitch.common.util;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Hive 连接预处理工具类,在读写 Hive 表前设置会话参数(列名不带表前缀),并对事务表开启并发与事务管理器支持。
 */
public final class HivePrepareUtils {

    private final static String HIVE_SQL_1 = "set hive.resultset.use.unique.column.names=false";

    private final static String HIVE_SQL_2 = "set hive.support.concurrency=true";

    private final static String HIVE_SQL_3 = "set hive.txn.manager = org.apache.hadoop.hive.ql.lockmgr.DbTxnManager";

    /**
     * 初始化 {@code HivePrepareUtils}。
     */
    private HivePrepareUtils() {
    }

    /**
     * 设置结果集合列名称非唯一。
     */
    public static void setResultSetColumnNameNotUnique(Connection connection)
            throws SQLException {
        executeWithoutResultSet(connection, HIVE_SQL_1);
    }

    /**
     * 设置 Hive 读写所需会话参数，并按表属性启用事务配置。
     */
    public static void prepare(Connection connection, String schema, String table)
            throws SQLException {
        executeWithoutResultSet(connection, HIVE_SQL_1);
        if (isTransactionalTable(connection, schema, table)) {
            executeWithoutResultSet(connection, HIVE_SQL_2);
            executeWithoutResultSet(connection, HIVE_SQL_3);
        }
    }

    /**
     * 判断事务表。
     */
    private static boolean isTransactionalTable(Connection connection, String schema, String table)
            throws SQLException {
        String fullTableName = String.format("`%s`.`%s`", schema, table);
        String sql = String.format("DESCRIBE FORMATTED %s", fullTableName);
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String dataType = rs.getString("data_type");
                String comment = rs.getString("comment");
                if (dataType != null
                        && comment != null
                        && dataType.startsWith("transactional")
                        && comment.startsWith("true")) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * 执行不需要读取结果集的数据库语句。
     */
    private static boolean executeWithoutResultSet(Connection connection, String sql)
            throws SQLException {
        try (Statement st = connection.createStatement()) {
            return st.execute(sql);
        }
    }
}
