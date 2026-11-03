package cn.iocoder.yudao.module.dataforge.framework.mybatis.core.type;

import cn.iocoder.yudao.module.dataforge.service.access.config.AccessTaskConfig;
import cn.iocoder.yudao.module.dataforge.service.access.config.AccessTaskConfigCodec;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.*;

/** 仅持久化版本化业务白名单，PostgreSQL JSONB 通过 OTHER 绑定。 */
@MappedTypes(AccessTaskConfig.class)
@MappedJdbcTypes(JdbcType.OTHER)
public class AccessTaskConfigTypeHandler extends BaseTypeHandler<AccessTaskConfig> {

    /**
     * 将非空参数转换后绑定到 JDBC 预编译语句。
     *
     * @param ps JDBC 预编译语句
     * @param i 参数或列下标
     * @param value 待处理的值
     * @param type 类型标识
     */
    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, AccessTaskConfig value,
            JdbcType type) throws SQLException {
        try {
            ps.setObject(i, AccessTaskConfigCodec.write(value), Types.OTHER);
        } catch (IllegalArgumentException ignored) {
            throw new SQLException("数据接入业务配置校验失败");
        }
    }

    /**
     * 读取 JDBC 结果并转换为当前类型，保留数据库空值语义。
     *
     * @param rs JDBC 查询结果集
     * @param name 名称
     * @return 访问任务配置
     */
    @Override
    public AccessTaskConfig getNullableResult(ResultSet rs, String name) throws SQLException {
        return parse(rs.getString(name));
    }

    /**
     * 读取 JDBC 结果并转换为当前类型，保留数据库空值语义。
     *
     * @param rs JDBC 查询结果集
     * @param index 下标
     * @return 访问任务配置
     */
    @Override
    public AccessTaskConfig getNullableResult(ResultSet rs, int index) throws SQLException {
        return parse(rs.getString(index));
    }

    /**
     * 读取 JDBC 结果并转换为当前类型，保留数据库空值语义。
     *
     * @param cs JDBC 存储过程调用
     * @param index 下标
     * @return 访问任务配置
     */
    @Override
    public AccessTaskConfig getNullableResult(CallableStatement cs, int index) throws SQLException {
        return parse(cs.getString(index));
    }

    /**
     * 解析访问任务配置类型。
     *
     * @param json JSON 内容
     * @return 访问任务配置
     */
    private AccessTaskConfig parse(String json) throws SQLException {
        try {
            return AccessTaskConfigCodec.read(json);
        } catch (IllegalArgumentException ignored) {
            throw new SQLException("数据接入业务配置版本或白名单不合法，请检查配置迁移");
        }
    }
}
