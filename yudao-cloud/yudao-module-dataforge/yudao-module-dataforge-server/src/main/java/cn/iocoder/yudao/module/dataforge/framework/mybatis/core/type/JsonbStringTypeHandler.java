package cn.iocoder.yudao.module.dataforge.framework.mybatis.core.type;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/**
 * PostgreSQL jsonb 字段与 Java String 的映射：
 * 写入时以 Types.OTHER 提交，避免 jsonb 与 varchar 类型不匹配报错
 *
 * @author Li
 */
public class JsonbStringTypeHandler extends BaseTypeHandler<String> {

    /**
     * 将非空参数转换后绑定到 JDBC 预编译语句。
     *
     * @param ps JDBC 预编译语句
     * @param i 参数或列下标
     * @param parameter 待绑定的参数值
     * @param jdbcType JDBC 类型
     */
    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter,
            JdbcType jdbcType) throws SQLException {
        ps.setObject(i, parameter, Types.OTHER);
    }

    /**
     * 读取 JDBC 结果并转换为当前类型，保留数据库空值语义。
     *
     * @param rs JDBC 查询结果集
     * @param columnName 列名
     * @return  JDBC 结果并转换为当前类型，保留数据库空值语义文本
     */
    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return rs.getString(columnName);
    }

    /**
     * 读取 JDBC 结果并转换为当前类型，保留数据库空值语义。
     *
     * @param rs JDBC 查询结果集
     * @param columnIndex 列下标
     * @return  JDBC 结果并转换为当前类型，保留数据库空值语义文本
     */
    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getString(columnIndex);
    }

    /**
     * 读取 JDBC 结果并转换为当前类型，保留数据库空值语义。
     *
     * @param cs JDBC 存储过程调用
     * @param columnIndex 列下标
     * @return  JDBC 结果并转换为当前类型，保留数据库空值语义文本
     */
    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return cs.getString(columnIndex);
    }

}
