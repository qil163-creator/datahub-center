package cn.iocoder.yudao.framework.dbswitch.pgwriter.bulkprocessor.handler;

import cn.iocoder.yudao.framework.dbswitch.pgwriter.IPgBulkInsert;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.util.PostgreSqlUtils;
import org.postgresql.PGConnection;

import java.sql.Connection;
import java.util.List;
import java.util.function.Supplier;

/**
 * 批量写入处理器实现,从连接工厂获取连接并解包为 PGConnection,通过 COPY API 将一批实体写入 PostgreSQL。
 */
public class BulkWriteHandler<TEntity> implements IBulkWriteHandler<TEntity> {

    private final IPgBulkInsert<TEntity> client;

    private final Supplier<Connection> connectionFactory;

    /**
     * 初始化 {@code BulkWriteHandler}。
     */
    public BulkWriteHandler(IPgBulkInsert<TEntity> client, Supplier<Connection> connectionFactory) {
        this.client = client;
        this.connectionFactory = connectionFactory;
    }

    /**
     * 获取独立连接并通过 PostgreSQL COPY API 写入当前实体批次。
     */
    @Override
    public void write(List<TEntity> entities) throws Exception {
        // 获取新连接并在 try-with-resources 块中执行，确保连接正确关闭：
        try (Connection connection = connectionFactory.get()) {
            // 获取底层 PGConnection，以便包装 COPY API：
            final PGConnection pgConnection = PostgreSqlUtils.getPGConnection(connection);
            // 最后使用 COPY API 保存全部实体：
            client.saveAll(pgConnection, entities.stream());
        }
    }
}
