package cn.iocoder.yudao.framework.dbswitch.dbwriter.dm;

import org.springframework.jdbc.core.SqlTypeValue;

import java.sql.Types;
import java.util.List;

/**
 * DM INSERT 路径的空值绑定适配器：DM 驱动通过
 * {@code setObject(..., BLOB/CLOB)} 绑定空 {@code byte[]}/{@code String} 时将其存为 NULL，
 * 而 {@code setBytes}/{@code setString} 会区分空值，已在 8.1.5.60 上探测验证。
 */
public final class DmLobBatch implements AutoCloseable {

    private final List<Object[]> rows;

    /**
     * 校验构造参数并初始化 {@code DmLobBatch}。
     */
    public DmLobBatch(List<Object[]> records, int[] inputOrderTypes) {
        if (inputOrderTypes == null) {
            throw new IllegalStateException("DM input types are not prepared");
        }
        this.rows = new java.util.ArrayList<>(records.size());
        for (var record : records) {
            Object[] converted = new Object[record.length];
            for (int i = 0; i < record.length; i++) {
                converted[i] = convert(record[i], inputOrderTypes[i]);
            }
            rows.add(converted);
        }
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
        if (targetType == Types.BLOB && value instanceof byte[] bytes && bytes.length == 0) {
            return (SqlTypeValue) (ps, index, sqlType, typeName) -> ps.setBytes(index, bytes);
        }
        if ((targetType == Types.CLOB || targetType == Types.NCLOB) && value instanceof String text && text.isEmpty()) {
            return (SqlTypeValue) (ps, index, sqlType, typeName) -> {
                if (targetType == Types.NCLOB) {
                    ps.setNString(index, text);
                } else {
                    ps.setString(index, text);
                }
            };
        }
        return value;
    }

    /**
     * 关闭 {@code DmLobBatch} 当前持有的资源。
     */
    @Override
    public void close() {
        // 不持有资源，仅复制数组及字符串值。
    }
}
