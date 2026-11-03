package cn.iocoder.yudao.framework.dbswitch.dbwriter.oracle;

import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.common.util.JdbcValueConversions;
import org.springframework.jdbc.core.SqlTypeValue;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.StringReader;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/** JDBC 写入之前先物化源端定位器；仅负责临时绑定流。 */
public final class OracleLobBatch implements AutoCloseable {

    private final List<Closeable> resources = new ArrayList<>();

    private final List<Object[]> rows;

    /**
     * 使用给定参数初始化 {@code OracleLobBatch}。
     */
    public OracleLobBatch(List<Object[]> records, int[] inputOrderTypes) {
        this(records, inputOrderTypes, cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits.defaults());
    }

    /**
     * 校验构造参数并初始化 {@code OracleLobBatch}。
     */
    public OracleLobBatch(List<Object[]> records, int[] inputOrderTypes,
            cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits limits) {
        if (inputOrderTypes == null) {
            throw new IllegalStateException("Oracle input types are not prepared");
        }
        this.rows = BatchValueConverter.convert(records, inputOrderTypes.length, limits,
                (value, column) -> convert(value, inputOrderTypes[column]));
    }

    /**
     * 返回数据行。
     */
    public List<Object[]> rows() {
        return rows;
    }

    /**
     * 按目标类型约定转换当前输入值。
     */
    private Object convert(Object value, int targetType) {
        if (value == null) {
            return null;
        }
        return switch (targetType) {
            case Types.CLOB, Types.NCLOB -> {
                String text = JdbcValueConversions.toText(value);
                if (text.isEmpty()) {
                    // ojdbc 将空读取器或字符串存为 NULL；仅 empty_lob 定位器能将 '' 保留为空值。
                    yield (SqlTypeValue) (ps, index, sqlType, typeName) -> ps.setClob(index, emptyClob());
                }
                yield (SqlTypeValue) (ps, index, sqlType, typeName) -> {
                    StringReader reader = new StringReader(text);
                    resources.add(reader);
                    if (targetType == Types.NCLOB) {
                        ps.setNClob(index, reader, text.length());
                    } else {
                        ps.setClob(index, reader, text.length());
                    }
                };
            }
            case Types.BLOB -> {
                byte[] bytes = JdbcValueConversions.toBytes(value);
                if (bytes.length == 0) {
                    // ojdbc 将空流存为 NULL；仅 empty_lob 定位器能将 '' 保留为空值。
                    yield (SqlTypeValue) (ps, index, sqlType, typeName) -> ps.setBlob(index, emptyBlob());
                }
                yield (SqlTypeValue) (ps, index, sqlType, typeName) -> {
                    ByteArrayInputStream stream = new ByteArrayInputStream(bytes);
                    resources.add(stream);
                    ps.setBlob(index, stream, bytes.length);
                };
            }
            case Types.ROWID, Types.ARRAY, Types.REF, Types.SQLXML ->
                    throw new IllegalArgumentException("Unsupported Oracle target JDBC type " + targetType);
            default -> value;
        };
    }

    // ojdbc 是仅运行时使用的可选依赖，oracle.sql.CLOB/BLOB.empty_lob() 不能进入编译
    // 类路径，因此根据运行时类路径中的驱动，反射创建空定位器。

    /**
     * 通过 Oracle 驱动创建空字符大对象占位值。
     */
    private static java.sql.Clob emptyClob() {
        try {
            return (java.sql.Clob) MethodHandles.lookup().findStatic(
                            Class.forName("oracle.sql.CLOB"), "empty_lob",
                            MethodType.methodType(Class.forName("oracle.sql.CLOB")))
                    .invoke();
        } catch (Throwable failure) {
            throw new IllegalStateException("Oracle empty CLOB binding requires the ojdbc driver", failure);
        }
    }

    /**
     * 通过 Oracle 驱动创建空二进制大对象占位值。
     */
    private static java.sql.Blob emptyBlob() {
        try {
            return (java.sql.Blob) MethodHandles.lookup().findStatic(
                            Class.forName("oracle.sql.BLOB"), "empty_lob",
                            MethodType.methodType(Class.forName("oracle.sql.BLOB")))
                    .invoke();
        } catch (Throwable failure) {
            throw new IllegalStateException("Oracle empty BLOB binding requires the ojdbc driver", failure);
        }
    }

    /**
     * 关闭 {@code OracleLobBatch} 当前持有的资源。
     */
    @Override
    public void close() {
        // 此处仅负责内存读取器及流；源端定位器已释放。
        for (Closeable resource : resources) {
            try {
                resource.close();
            } catch (java.io.IOException impossible) {
                throw new IllegalStateException("Oracle binding cleanup failed");
            }
        }
        resources.clear();
    }
}
