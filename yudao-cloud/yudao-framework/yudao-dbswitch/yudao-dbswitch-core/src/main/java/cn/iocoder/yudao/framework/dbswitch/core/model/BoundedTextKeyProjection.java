package cn.iocoder.yudao.framework.dbswitch.core.model;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;

/**
 * 精确 UTF-8 文本身份的方言投影。identitySql 用于精确比较，groupSql 用于有界分组/排序；
 * valueSql 只返回上限内的完整字节或 NULL，绝不截断；typeSql 不携带原值。
 * 调用方在同一结果集核验原生类型后才能解释值。无连接、事务或业务状态所有权。
 */
public interface BoundedTextKeyProjection {

    /**
     * 生成当前对象身份或标识列的 SQL 定义。
     */
    String identitySql();

    /** 有界非 LOB 的精确分组表达式，超限/NULL 返回 NULL；不依赖会话的 LOB 排序前缀长度。 */
    String groupSql();

    /**
     * 按目标类型和数据库方言生成值表达式。
     */
    String valueSql();

    /**
     * 按当前列属性生成数据库类型 SQL。
     */
    String typeSql();

    /** 0 表示类型未经验证；不可由转成文本的结果反推源字段类型。 */
    int jdbcType(ResultSetMetaData metadata, int typeColumn) throws SQLException;

    /** 某些原生传输协议会丢失类型；可改为启用当前行提供的有界验证信息。 */
    default boolean requiresRowTypeWitness() {
        return false;
    }

    /** 不推进或关闭结果集，也不读取键载荷。 */
    default int jdbcType(java.sql.ResultSet rows, int typeColumn) throws SQLException {
        return jdbcType(rows.getMetaData(), typeColumn);
    }

    /**
     * 说明当前查询路径是否要求显式添加外层数量限制。
     */
    default boolean requiresExplicitOuterLimit() {
        return false;
    }

    /** 有界的精确值过滤表达式，返回方言安全常量，不依赖会话反斜杠转义或 collation。 */
    String equalitySql(String value);
}
