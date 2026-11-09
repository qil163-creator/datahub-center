package cn.iocoder.yudao.module.supply.service.dataapi;

import cn.iocoder.yudao.framework.dbswitch.core.util.QueryTimeoutConnection;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 真实 PostgreSQL 上验证语句超时由驱动取消执行；未提供 -Ddatahub.test.postgres.url 时跳过。
 */
class DataApiQueryTimeoutPostgreSqlTest {

    @Test
    void slowStatementIsCancelledByTheDriverAndRecognisedAsTimeout() throws Exception {
        String url = System.getProperty("datahub.test.postgres.url");
        Assumptions.assumeTrue(url != null && url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]{1,5}/[A-Za-z0-9_]+"),
                "未配置本地测试 PostgreSQL");
        String user = System.getProperty("datahub.test.postgres.user", "dataforge_test");
        try (Connection raw = DriverManager.getConnection(url, user, "")) {
            Connection bounded = QueryTimeoutConnection.wrap(raw, 1);
            long started = System.nanoTime();
            Throwable failure;
            try (PreparedStatement statement = bounded.prepareStatement("SELECT pg_sleep(10)")) {
                failure = catchThrowable(statement::executeQuery);
            }
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

            assertThat(failure).isInstanceOf(SQLException.class);
            assertThat(QueryTimeoutConnection.isQueryTimeout(new RuntimeException("查询失败", failure))).isTrue();
            assertThat(elapsedMillis).isLessThan(5_000);
            // 取消后连接仍可继续使用（不是靠断开连接实现的）
            try (PreparedStatement next = bounded.prepareStatement("SELECT 1");
                 var rs = next.executeQuery()) {
                assertThat(rs.next()).isTrue();
            }
        }
    }
}
