package cn.iocoder.yudao.framework.dbswitch.pgwriter.model;

import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.PgBinaryWriter;

import java.util.function.BiConsumer;

/**
 * 列定义模型,持有列名及把实体字段值写入二进制流的写入函数,是 COPY 列映射的基本单元。
 */
public class ColumnDefinition<TEntity> {

    private final String columnName;

    private final BiConsumer<PgBinaryWriter, TEntity> write;

    /**
     * 初始化 {@code ColumnDefinition}。
     */
    public ColumnDefinition(String columnName, BiConsumer<PgBinaryWriter, TEntity> write) {
        this.columnName = columnName;
        this.write = write;
    }

    /**
     * 返回列名。
     */
    public String getColumnName() {
        return columnName;
    }

    /**
     * 返回写入。
     */
    public BiConsumer<PgBinaryWriter, TEntity> getWrite() {
        return write;
    }

    /**
     * 返回当前对象的文本表示。
     */
    @Override
    public String toString() {
        return String
                .format("ColumnDefinition (ColumnName = {%1$s}, Serialize = {%2$s})", columnName, write);
    }
}
