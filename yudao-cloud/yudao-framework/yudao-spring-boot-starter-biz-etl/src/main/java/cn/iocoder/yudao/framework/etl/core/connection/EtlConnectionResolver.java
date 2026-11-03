package cn.iocoder.yudao.framework.etl.core.connection;

/**
 * 数据源解析回调：由业务模块实现（如按数据源编号取连接信息并解密密码）
 *
 * @author Li
 */
@FunctionalInterface
public interface EtlConnectionResolver {

    /**
     * 按数据源编号解析连接信息
     *
     * @param databaseId 数据源编号
     * @return 连接信息（解析失败应抛出带可读消息的异常）
     */
    EtlConnectionInfo resolve(Long databaseId);

}
