package cn.iocoder.yudao.module.governance.service.master;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.exception.ErrorCode;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredSourceGenerations;
import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.util.DataSourceUtils;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseConnectionRespDTO;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.stereotype.Component;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;

/**
 * 主数据 → 数据源连接信息解析器
 *
 * 数据源实例由 dataforge 模块统一管理，通过 RPC 现取现用（密码不在本模块落库），
 * 与 srt-cloud 固定"项目库"不同，本项目的主数据物理表建在模型显式绑定的数据源上
 *
 * @author Li
 */
@Component
public class MasterDatabaseResolver {

    @Resource
    private DatabaseApi databaseApi;

    @Resource
    private ConfiguredJdbcDrivers configuredJdbcDrivers = ConfiguredJdbcDrivers.empty();

    @Resource
    private cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions dbSwitchOptions =
            cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions.defaults();

    @Resource
    private ConfiguredSourceGenerations configuredSourceGenerations = ConfiguredSourceGenerations.empty();

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

        private String schema;

        private String configurationRevision;

        /** 提供方原始配置修订；与本节点驱动/受管代际组合修订分开，供跨服务批次绑定。 */
        private String providerConfigurationRevision;

        private Integer usageType;

        /** 仅在本进程解析后使用，不进入 API/持久化凭证或日志。 */
        @JsonIgnore
        @Getter(AccessLevel.NONE)
        @Setter(AccessLevel.NONE)
        @ToString.Exclude
        private transient ConfiguredJdbcDrivers.Binding driverBinding;

        /** 仅允许可信部署配置固定，DataForge RPC 或浏览器不得提供。 */
        @JsonIgnore
        @Setter(AccessLevel.NONE)
        @ToString.Exclude
        private transient SourceGenerationPin sourceGenerationPin;

    }

    /**
     * 按数据源编号解析连接信息（密码已解密）
     *
     * @param databaseId     数据源编号
     * @param notExistsError 数据源不存在时抛出的错误码
     */
    public ConnectionInfo resolve(Long databaseId, ErrorCode notExistsError) {
        DatabaseConnectionRespDTO dto = databaseApi.getDatabaseConnection(databaseId).getCheckedData();
        if (dto == null) {
            throw exception(notExistsError);
        }
        ConnectionInfo info = new ConnectionInfo();
        info.setDatabaseId(dto.getId());
        info.setName(dto.getName());
        info.setProductType(ProductTypeEnum.valueOf(dto.getProductType()));
        info.setJdbcUrl(dto.getJdbcUrl());
        info.setUsername(dto.getUsername());
        info.setPassword(dto.getPassword());
        info.setSchema(StrUtil.blankToDefault(dto.getSchema(), ""));
        info.setConfigurationRevision(dto.getConfigurationRevision());
        info.setProviderConfigurationRevision(dto.getConfigurationRevision());
        info.setUsageType(dto.getUsageType());
        info.driverBinding = configuredJdbcDrivers.find(info.getProductType()).orElse(null);
        if (info.driverBinding != null) {
            info.driverBinding.verifyUnchanged();
            // 路径可随节点不同，内容/类/产品必须一致；提供方无版本时不能自行伪造一个已证明版本。
            String revision = info.getConfigurationRevision();
            if (StrUtil.isNotBlank(revision)) {
                info.setConfigurationRevision("jdbc-v1:" + DigestUtil.sha256Hex("GOV_CONNECTION_DRIVER_V1\0"
                        + revision.length() + ":" + revision + "\0" + info.driverBinding.fingerprint()));
            }
        }
        info.sourceGenerationPin = configuredSourceGenerations.find(info.getProductType(), info.getJdbcUrl(),
                info.getUsername()).orElse(null);
        if (info.sourceGenerationPin != null && StrUtil.isNotBlank(info.getConfigurationRevision())) {
            String revision = info.getConfigurationRevision();
            info.setConfigurationRevision("source-v1:" + DigestUtil.sha256Hex("GOV_CONNECTION_SOURCE_V1\0"
                    + revision.length() + ":" + revision + "\0" + info.sourceGenerationPin.fingerprint()));
        }
        return info;
    }

    /**
     * 基于连接信息创建小型连接池（调用方负责关闭）
     */
    public HikariDataSource createDataSource(ConnectionInfo conn) {
        validateNodeBindings(conn);
        TargetDataSourceProperties properties = new TargetDataSourceProperties();
        properties.setUrl(queryJdbcUrl(conn));
        properties.setDriverClassName(conn.getProductType().getDriveClassName());
        properties.setTargetProductType(conn.getProductType());
        properties.setUsername(conn.getUsername());
        properties.setPassword(conn.getPassword());
        properties.setPoolSize(4);
        return DataSourceUtils.createTargetDataSource(properties, null, null, conn.driverBinding,
                configuredJdbcDrivers.runtimes(), dbSwitchOptions);
    }

    /** 将本节点已解析的绑定交给限时查询运行时，继续保留受管源代际门禁。 */
    public cn.iocoder.yudao.framework.dbswitch.data.query.QueryRuntime.ConnectionSpec queryConnectionSpec(
            ConnectionInfo conn) {
        validateNodeBindings(conn);
        return new cn.iocoder.yudao.framework.dbswitch.data.query.QueryRuntime.ConnectionSpec(
                conn.getProductType(), queryJdbcUrl(conn), conn.getUsername(), conn.getPassword(),
                null, conn.getConfigurationRevision());
    }

    /**
     * 读取数据源连接配置中的 JDBC URL。
     */
    private static String queryJdbcUrl(ConnectionInfo conn) {
        return conn.sourceGenerationPin == null ? conn.getJdbcUrl()
                : ConfiguredSourceGenerations.managedJdbcUrl(conn.getJdbcUrl());
    }

    /**
     * 校验主数据基础的节点绑定，不满足约束时拒绝当前操作。
     */
    private void validateNodeBindings(ConnectionInfo conn) {
        if (conn.sourceGenerationPin != configuredSourceGenerations.find(conn.getProductType(), conn.getJdbcUrl(),
                conn.getUsername()).orElse(null)) {
            throw new IllegalArgumentException(
                    "Connection must be resolved with this node's configured source generation");
        }
        if (conn.driverBinding != configuredJdbcDrivers.find(conn.getProductType()).orElse(null)) {
            throw new IllegalArgumentException("Connection must be resolved with this node's configured JDBC driver");
        }
    }

}
