package cn.iocoder.yudao.framework.dbswitch.core.model;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;

/**
 * 有界整数键投影。valueSql 保留 SQL NULL，并完整输出支持范围的十进制整数；
 * typeSql 只传输类型见证，不传输原始键载荷。调用方必须先验证同一查询的实际结果元数据，
 * 再读取整数值；不能从转换后的值推断原始类型。0 表示未支持，禁止继续数值转换。
 * 不负责业务统计、摘要、连接或事务，不声明跨库等价或读取快照。
 */
public interface BoundedIntegerKeyProjection extends BoundedKeyComponentProjection {

    /**
     * 按目标类型和数据库方言生成值表达式。
     */
    String valueSql();

    /**
     * 按当前列属性生成数据库类型 SQL。
     */
    String typeSql();

    /**
     * 确定当前列或值对应的 JDBC 类型。
     */
    int jdbcType(ResultSetMetaData metadata, int typeColumn) throws SQLException;

    /** 整数的十进制表示也是精确且有界的元组组件。 */
    @Override
    default String groupSql() {
        return valueSql();
    }

    /**
     * 有序投影读取是否除 JDBC maxRows 外，还需要显式的外层 SQL 上限。
     * StarRocks 3.5/Connector-J 对隐式 sql_select_limit 可能引入无序的最终交换步骤。
     * 调用方必须使用已校验的上限（包括检测溢出的哨兵值）；绝不移除 maxRows。
     * 默认 false 保留现有产品的 SQL 和已持久化的查询指纹。
     */
    default boolean requiresExplicitOuterLimit() {
        return false;
    }
}
