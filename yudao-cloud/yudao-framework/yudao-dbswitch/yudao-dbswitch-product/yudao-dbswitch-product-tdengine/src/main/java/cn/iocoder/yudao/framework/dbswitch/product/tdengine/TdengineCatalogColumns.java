package cn.iocoder.yudao.framework.dbswitch.product.tdengine;

import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableSchemaMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.schema.nativecatalog.TdengineNativeCatalog;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 公开 TDengine 3.3.8.8 列目录。DESCRIBE 读取由 {@link TdengineNativeCatalog} 实现。
 * 不读取 {@code information_schema.ins_columns}，因此 {@code col_length} 不是目录长度。
 * 标签不是普通列。不使用 {@code MYSQL_COLUMNS}。不提交，也不拥有连接。
 */
public final class TdengineCatalogColumns {

    public static final String VERSION_SQL = TdengineNativeCatalog.VERSION_SQL;

    /** 标识符加引号后追加限定名称。不使用 {@code ins_columns}。 */
    public static final String DESCRIBE_SQL = TdengineNativeCatalog.DESCRIBE_SQL;

    /** 仅为数据库精度，不是列属性，也不是 {@code ins_columns.col_scale}。 */
    public static final String PRECISION_SQL = TdengineNativeCatalog.PRECISION_SQL;

    /**
     * 初始化 {@code TdengineCatalogColumns}。
     */
    private TdengineCatalogColumns() {
    }

    /** 仅接受 ms、us 和 ns。其他值，包括 null，均不是已读取结果。 */
    public static Integer timestampDigits(String databasePrecision) {
        return TdengineNativeCatalog.timestampDigits(databasePrecision);
    }

    /** 无法读取数据库精度时标记缺失。绝不将读取缺失变为数字。 */
    public static MetadataValue<Integer> timestampScale(String databasePrecision) {
        return TdengineNativeCatalog.timestampScale(databasePrecision);
    }

    /**
     * DESCRIBE 类型标记，以及有长度类型的 DESCRIBE 长度。不改写标记：
     * 存储的 {@code INT} 保持 {@code INT}，存储的 {@code VARCHAR} 保持 {@code VARCHAR}。
     */
    public static String nativeType(String describeType, int describeLength) {
        return TdengineNativeCatalog.nativeType(describeType, describeLength);
    }

    /**
     * 读取TDengine原生目录并构建表结构元数据。
     */
    public static TableSchemaMetadata read(Connection connection, String schema, String table,
            int timeoutSeconds) throws SQLException {
        return TdengineNativeCatalog.read(connection, schema, table, timeoutSeconds);
    }

    /** JDBC nullable 是驱动标志。为 null 的 {@code ins_columns.col_nullable} 单元格不是值。 */
    public static MetadataValue<Boolean> nullable(int jdbcNullable) {
        return TdengineNativeCatalog.nullable(jdbcNullable);
    }

    /** 仅对有长度类型使用 DESCRIBE 长度。INT 的宽度 4 不是字符长度。 */
    public static MetadataValue<Long> characterLength(String nativeType, int describeLength) {
        return TdengineNativeCatalog.characterLength(nativeType, describeLength);
    }
}
