package cn.iocoder.yudao.framework.dbswitch.pgwriter;

import org.postgresql.PGConnection;

import java.sql.SQLException;
import java.util.stream.Stream;

/**
 * PostgreSQL 批量插入接口,定义基于 COPY 协议将实体流批量写入数据库的统一入口。
 */
public interface IPgBulkInsert<TEntity> {

    /**
     * 逐行编码并保存当前批次中的全部数据。
     */
    void saveAll(PGConnection connection, Stream<TEntity> entities) throws SQLException;
}
