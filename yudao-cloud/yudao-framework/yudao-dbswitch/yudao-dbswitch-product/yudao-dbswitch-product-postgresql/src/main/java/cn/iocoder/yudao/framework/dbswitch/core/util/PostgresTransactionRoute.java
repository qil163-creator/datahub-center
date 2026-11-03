package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.hutool.crypto.digest.DigestUtil;

import java.sql.ResultSet;
import java.sql.SQLException;

/** 读写双方共用相同的路由摘要协议；不是原生集群身份或恢复/克隆代际证明。 */
public final class PostgresTransactionRoute {

    public static final String COLUMNS = """
            pg_catalog.current_database(), d.oid::text, session_user, current_user,
            pg_catalog.inet_server_addr()::text, pg_catalog.inet_server_port()
            """;

    public static final String FROM = " FROM pg_catalog.pg_database d WHERE d.datname = pg_catalog.current_database()";

    /**
     * 初始化 {@code PostgresTransactionRoute}。
     */
    private PostgresTransactionRoute() {
    }

    /**
     * 根据当前内容计算结构指纹，供一致性比较使用。
     */
    public static String fingerprint(ResultSet rows, int firstColumn) throws SQLException {
        var material = new StringBuilder("PG_WRITE_ROUTE_V1");
        for (int i = firstColumn; i < firstColumn + 6; i++) {
            String part = rows.getString(i);
            if (part == null || part.isBlank() || part.length() > 1024) {
                throw new SQLException("Transaction route unavailable");
            }
            material.append('\0').append(part);
        }
        return DigestUtil.sha256Hex(material.toString());
    }
}
