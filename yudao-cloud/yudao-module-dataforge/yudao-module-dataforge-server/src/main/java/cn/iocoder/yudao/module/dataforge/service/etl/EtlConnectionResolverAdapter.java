package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.iocoder.yudao.framework.etl.core.connection.EtlConnectionInfo;
import cn.iocoder.yudao.framework.etl.core.connection.EtlConnectionResolver;
import cn.iocoder.yudao.module.dataforge.service.access.AccessDatabaseResolver;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATABASE_NOT_EXISTS;

/**
 * ETL 引擎数据源解析适配器：数据源实例 → 引擎连接信息（密码现取现解密）
 *
 * @author Li
 */
@Component
public class EtlConnectionResolverAdapter implements EtlConnectionResolver {

    @Resource
    private AccessDatabaseResolver databaseResolver;

    /**
     * 解析ETL连接解析器适配器配置或身份。
     *
     * @param databaseId 数据源编号
     * @return ETL连接信息
     */
    @Override
    public EtlConnectionInfo resolve(Long databaseId) {
        AccessDatabaseResolver.ConnectionInfo conn = databaseResolver.resolve(databaseId, DATABASE_NOT_EXISTS);
        EtlConnectionInfo info = new EtlConnectionInfo();
        info.setDatabaseId(conn.getDatabaseId());
        info.setName(conn.getName());
        info.setProductType(conn.getProductType());
        info.setJdbcUrl(conn.getJdbcUrl());
        info.setUsername(conn.getUsername());
        info.setPassword(conn.getPassword());
        info.setSchema(conn.getSchema());
        info.setConfigurationRevision(conn.getConfigurationRevision());
        return info;
    }

}
