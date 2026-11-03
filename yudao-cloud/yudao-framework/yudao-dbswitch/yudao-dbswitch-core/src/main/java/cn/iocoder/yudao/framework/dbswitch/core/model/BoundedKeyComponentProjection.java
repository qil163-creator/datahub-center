package cn.iocoder.yudao.framework.dbswitch.core.model;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;

/**
 * 复合身份分量的有界投影：valueSql 返回完整 UTF-8 字节或 NULL，typeSql 返回无载荷原生类型见证。
 * 有符号整数使用无前导零的十进制，文本保持精确字符序列；调用方必须将类型与长度一同编码，
 * 不能仅连接分量值或把逐列集合当作元组集合。groupSql 为有界非 LOB 精确分组身份。
 * 不拥有连接、事务、统计策略或摘要算法；jdbcType=0 时不得解释该分量载荷。
 */
public interface BoundedKeyComponentProjection {

    /**
     * 按目标类型和数据库方言生成值表达式。
     */
    String valueSql();

    /**
     * 按当前列属性生成数据库类型 SQL。
     */
    String typeSql();

    /**
     * 生成按当前键列分组的 SQL 片段。
     */
    String groupSql();

    /**
     * 确定当前列或值对应的 JDBC 类型。
     */
    int jdbcType(ResultSetMetaData metadata, int column) throws SQLException;

    /**
     * 判断当前类型处理是否要求数据行类型证据。
     */
    default boolean requiresRowTypeWitness() {
        return false;
    }

    /** 当前行提供的有界原生验证信息，不持有游标或连接。 */
    default int jdbcType(java.sql.ResultSet rows, int column) throws SQLException {
        return jdbcType(rows.getMetaData(), column);
    }

    /** 仅在校验此行原生类型后读取。默认实现保留现有驱动契约。 */
    default byte[] readBytes(java.sql.ResultSet rows, int column) throws SQLException {
        return rows.getBytes(column);
    }
}
