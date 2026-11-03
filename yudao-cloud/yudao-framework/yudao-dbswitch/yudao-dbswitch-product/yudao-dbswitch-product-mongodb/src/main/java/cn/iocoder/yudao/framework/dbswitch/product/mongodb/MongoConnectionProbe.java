package cn.iocoder.yudao.framework.dbswitch.product.mongodb;

import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionProbeRequest;
import cn.iocoder.yudao.framework.dbswitch.spi.ConnectionProbe;

import java.sql.Connection;
import java.sql.SQLException;

/** 共享既有原生协议探测，独立连接能力不构造元数据工厂。 */
public final class MongoConnectionProbe implements ConnectionProbe {

    /**
     * 探测目标连接或接口并返回可用性结果。
     */
    @Override
    public void probe(ConnectionProbeRequest request, JdbcOpener opener) throws Exception {
        try (var connection = opener.open()) {
            probe(connection, (int) request.timeout().toSeconds());
            if (request.database() != null && !request.database().isBlank()) {
                new cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseMongoDBImpl()
                        .probeNamespace(connection, request.database(), (int) request.timeout().toSeconds());
            }
        }
    }

    /**
     * 探测目标连接或接口并返回可用性结果。
     */
    public static void probe(Connection connection, int timeoutSeconds) throws SQLException {
        var session = MongoNativeSession.open(connection, 64 * 1024, timeoutSeconds);
        MongoReplies.success(session, session.command("admin",
                session.encode("{\"ping\":1,\"maxTimeMS\":" + (1000L * timeoutSeconds) + "}")));
    }
}
