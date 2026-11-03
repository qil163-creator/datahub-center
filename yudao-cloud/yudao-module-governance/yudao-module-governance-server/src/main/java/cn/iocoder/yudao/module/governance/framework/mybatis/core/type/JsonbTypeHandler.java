package cn.iocoder.yudao.module.governance.framework.mybatis.core.type;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/**
 * PostgreSQL jsonb 字段与 Java String 的映射：
 * 写入时以 Types.OTHER 提交，避免 "column is of type jsonb but expression is of type character varying" 报错
 *
 * @author Li
 */
@MappedTypes(String.class)
@MappedJdbcTypes(JdbcType.OTHER)
public class JsonbTypeHandler extends BaseTypeHandler<String> {

    /**
     * 将非空业务值序列化后绑定为 PostgreSQL JSONB 参数。
     */
    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter,
            JdbcType jdbcType) throws SQLException {
        ps.setObject(i, parameter, Types.OTHER);
    }

    /**
     * 读取可空结果。
     */
    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return rs.getString(columnName);
    }

    /**
     * 读取可空结果。
     */
    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getString(columnIndex);
    }

    /**
     * 读取可空结果。
     */
    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return cs.getString(columnIndex);
    }

}
