package cn.iocoder.yudao.module.dataforge.service.lineage;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.DatabaseNamespaceResolver;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseMapper;
import cn.iocoder.yudao.module.dataforge.service.access.AccessDatabaseResolver;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/**
 * 解析血缘分析所需的数据源信息：数据库产品与未限定表名的命名空间。
 * <p>
 * 只读取平台数据源记录，不解密凭据、不建立连接。
 */
@Component
public class LineageDatasourceResolver {

    @Resource
    private DatabaseMapper databaseMapper;
    @Resource
    private AccessDatabaseResolver databaseResolver;

    /**
     * 数据源信息。product 为 null 表示产品无法识别。
     */
    public record Datasource(Long id, ProductTypeEnum product, String defaultSchema) {
    }

    /**
     * 解析数据源；记录不存在时返回 null。
     */
    public Datasource resolve(Long databaseId) {
        if (databaseId == null) {
            return null;
        }
        DatabaseDO database = databaseMapper.selectById(databaseId);
        if (database == null) {
            return null;
        }
        ProductTypeEnum product;
        try {
            product = databaseResolver.resolveProductType(database);
        } catch (ServiceException unsupported) {
            return new Datasource(databaseId, null, null);
        }
        return new Datasource(databaseId, product, defaultSchema(product, database));
    }

    /**
     * 未限定表名使用的命名空间；无法证明时为 null。
     */
    private static String defaultSchema(ProductTypeEnum product, DatabaseDO database) {
        try {
            return DatabaseNamespaceResolver.resolve(product, database.getDatabaseName(), database.getDatabaseSchema())
                    .orElse(null);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
