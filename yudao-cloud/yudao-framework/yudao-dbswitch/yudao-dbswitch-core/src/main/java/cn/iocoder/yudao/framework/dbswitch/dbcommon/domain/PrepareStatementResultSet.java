package cn.iocoder.yudao.framework.dbswitch.dbcommon.domain;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.support.JdbcUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * JDBC连接及结果集实体参数定义类
 *
 * @author jrl
 */
@Slf4j
@Data
public class PrepareStatementResultSet implements AutoCloseable {

    private boolean isAutoCommit;

    private Connection connection;

    private PreparedStatement statement;

    private ResultSet resultset;

    /**
     * 关闭 {@code PrepareStatementResultSet} 当前持有的资源。
     */
    @Override
    public void close() {
        // 先关闭 ResultSet/Statement，再恢复 autoCommit，最后关闭连接：
        // 避免在游标仍打开时切换 autoCommit 触发部分驱动的隐式提交/游标失效问题
        JdbcUtils.closeResultSet(resultset);
        JdbcUtils.closeStatement(statement);
        if (connection != null) {
            try {
                connection.setAutoCommit(isAutoCommit);
            } catch (SQLException e) {
                log.warn("Jdbc Connect setAutoCommit() failed, error: {}", e.getMessage());
            }
        }
        JdbcUtils.closeConnection(connection);
    }
}
