package cn.iocoder.yudao.module.dataforge.service.access;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.exception.ErrorCode;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.DatabaseNamespaceResolver;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.databasetype.DatabaseTypeDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.databasetype.DatabaseTypeMapper;
import cn.iocoder.yudao.module.dataforge.service.database.DatabasePasswordEncryptUtils;
import jakarta.annotation.Resource;
import lombok.Data;
import org.springframework.stereotype.Component;


import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_ACCESS_PRODUCT_TYPE_UNSUPPORTED;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATABASE_NAMESPACE_INVALID;

/**
 * 数据源实例 → dbswitch 连接信息解析器
 *
 * 数据同步任务的 JSON 配置里不落连接串与密码，统一在执行/预览时通过本组件现取现解密，
 * 避免密码变更后任务失效，也符合“密码不出库”的安全约定
 *
 * @author Li
 */
@Component
public class AccessDatabaseResolver {

    @Resource
    private cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry products;

    @Resource
    private DatabaseMapper databaseMapper;

    @Resource
    private DatabaseTypeMapper databaseTypeMapper;

    /**
     * 解析后的连接信息
     */
    @Data
    public static class ConnectionInfo {

        private Long databaseId;

        private String name;

        private ProductTypeEnum productType;

        private String jdbcUrl;

        private String username;

        private String password;

        private String databaseName;

        private String schema;

        private String configurationRevision;

        private Integer usageType;

    }

    /**
     * 按数据源编号解析连接信息（密码已解密）
     *
     * @param databaseId     数据源编号
     * @param notExistsError 数据源不存在时抛出的错误码
     */
    public ConnectionInfo resolve(Long databaseId, ErrorCode notExistsError) {
        DatabaseDO database = databaseMapper.selectById(databaseId);
        if (database == null) {
            throw exception(notExistsError);
        }
        return resolve(database);
    }

    /**
     * 按数据源编号解析连接信息（密码已解密）
     *
     * @param database 数据源
     * @return 连接信息
     */
    public ConnectionInfo resolve(DatabaseDO database) {
        ConnectionInfo info = new ConnectionInfo();
        info.setDatabaseId(database.getId());
        info.setName(database.getName());
        info.setProductType(resolveProductType(database));
        info.setJdbcUrl(appendUrlParams(database.getJdbcUrl(), database.getExtendConfig()));
        info.setUsername(database.getUserName());
        info.setPassword(DatabasePasswordEncryptUtils.decrypt(database.getPassword(), database.getSecretKey()));
        info.setDatabaseName(database.getDatabaseName());
        // DB Switch 的 schema 字段表示表命名空间；catalog 类产品缺省使用配置的数据库名。
        try {
            info.setSchema(DatabaseNamespaceResolver.resolve(info.getProductType(),
                    database.getDatabaseName(), database.getDatabaseSchema()).orElse(""));
        } catch (IllegalArgumentException invalidNamespace) {
            throw exception(DATABASE_NAMESPACE_INVALID);
        }
        info.setConfigurationRevision(database.getUpdateTime() == null ? null : database.getUpdateTime().toString());
        info.setUsageType(database.getUsageType());
        return info;
    }

    /**
     * 数据库类型定义 → 已安装产品；共享驱动类和协议不构成产品身份证据。
     */
    public ProductTypeEnum resolveProductType(DatabaseDO database) {
        DatabaseTypeDO type = database.getDatabaseType() == null
                ? null
                : databaseTypeMapper.selectById(database.getDatabaseType().longValue());
        if (type == null) {
            throw exception(DATA_ACCESS_PRODUCT_TYPE_UNSUPPORTED, database.getDatabaseType());
        }
        String raw = StrUtil.blankToDefault(type.getTypeCode(), type.getTypeName());
        return products.resolve(raw).orElseThrow(() -> exception(DATA_ACCESS_PRODUCT_TYPE_UNSUPPORTED, raw));
    }

    /**
     * 追加URL参数。
     *
     * @param jdbcUrl JDBCURL
     * @param params 业务参数映射
     * @return 追加URL参数文本
     */
    private String appendUrlParams(String jdbcUrl, String params) {
        if (StrUtil.isBlank(jdbcUrl) || StrUtil.isBlank(params)) {
            return jdbcUrl;
        }
        if (jdbcUrl.contains(params)) {
            return jdbcUrl;
        }
        String sep = jdbcUrl.contains("?") ? "&" : "?";
        return jdbcUrl + sep + params;
    }

}
