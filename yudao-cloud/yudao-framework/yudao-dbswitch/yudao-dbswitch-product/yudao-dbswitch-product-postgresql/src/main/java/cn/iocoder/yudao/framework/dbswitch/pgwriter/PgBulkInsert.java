package cn.iocoder.yudao.framework.dbswitch.pgwriter;

import cn.iocoder.yudao.framework.dbswitch.pgwriter.configuration.Configuration;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.configuration.IConfiguration;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.exceptions.SaveEntityFailedException;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.mapping.AbstractMapping;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.PgBinaryWriter;
import org.postgresql.PGConnection;
import org.postgresql.copy.PGCopyOutputStream;

import java.sql.SQLException;
import java.util.Collection;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * PostgreSQL 批量插入实现类,基于 COPY 协议的二进制输出流,按列映射把实体逐行批量写入目标表。
 */
public class PgBulkInsert<TEntity> implements IPgBulkInsert<TEntity> {

    private final IConfiguration configuration;

    private final AbstractMapping<TEntity> mapping;

    /**
     * 使用给定参数初始化 {@code PgBulkInsert}。
     */
    public PgBulkInsert(AbstractMapping<TEntity> mapping) {
        this(new Configuration(), mapping);
    }

    /**
     * 校验构造参数并初始化 {@code PgBulkInsert}。
     */
    public PgBulkInsert(IConfiguration configuration, AbstractMapping<TEntity> mapping) {
        Objects.requireNonNull(configuration, "'configuration' has to be set");
        Objects.requireNonNull(mapping, "'mapping' has to be set");

        this.configuration = configuration;
        this.mapping = mapping;
    }

    /**
     * 逐行编码并保存当前批次中的全部数据。
     */
    @Override
    public void saveAll(PGConnection connection, Stream<TEntity> entities) throws SQLException {
        // 用自定义 Writer 包装 CopyOutputStream：
        try (PgBinaryWriter bw = new PgBinaryWriter(
                new PGCopyOutputStream(connection, mapping.getCopyCommand(), 1),
                configuration.getBufferSize())) {
            // 插入每一列：
            entities.forEach(entity -> saveEntitySynchonized(bw, entity));
        }
    }

    /**
     * 逐行编码并保存当前批次中的全部数据。
     */
    public void saveAll(PGConnection connection, Collection<TEntity> entities) throws SQLException {
        saveAll(connection, entities.stream());
    }

    /**
     * 按列映射将当前实体编码为 PostgreSQL COPY 数据行。
     */
    private void saveEntity(PgBinaryWriter bw, TEntity entity) throws SaveEntityFailedException {
        // 在 PostgreSQL 中开始新的一行：
        bw.startRow(mapping.getColumns().size());

        try {
            // 遍历每个列映射：
            mapping.getColumns().forEach(column -> {
                column.getWrite().accept(bw, entity);
            });
        } catch (Exception e) {
            throw new SaveEntityFailedException(e);
        }
    }

    /**
     * 在写入缓冲区的同步锁内编码并保存当前实体。
     */
    private void saveEntitySynchonized(PgBinaryWriter bw, TEntity entity)
            throws SaveEntityFailedException {
        synchronized (bw) {
            saveEntity(bw, entity);
        }
    }
}
