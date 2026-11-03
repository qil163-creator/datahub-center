package cn.iocoder.yudao.framework.dbswitch.config;

import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredSourceGenerations;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

/** 上下文持有的不可变产品及配置快照，以及部署驱动生命周期。 */
@AutoConfiguration
@EnableConfigurationProperties(DbSwitchProperties.class)
public class YudaoDbSwitchAutoConfiguration {

    /**
     * 创建 DB Switch 数据库产品能力注册表。
     */
    @Bean
    @ConditionalOnMissingBean
    public DbSwitchProductRegistry dbSwitchProductRegistry() {
        return DbSwitchProductRegistry.discover();
    }

    /**
     * 根据当前输入构建并返回 {@code ConfiguredSourceGenerations}。
     */
    @Bean
    @ConditionalOnMissingBean
    public ConfiguredSourceGenerations configuredSourceGenerations(DbSwitchProperties properties) {
        return new ConfiguredSourceGenerations(properties.getSourceGenerations());
    }

    /**
     * 根据当前输入构建并返回 {@code ConfiguredJdbcDrivers}。
     */
    @Bean
    @ConditionalOnMissingBean
    public ConfiguredJdbcDrivers configuredJdbcDrivers(DbSwitchProperties properties) {
        return new ConfiguredJdbcDrivers(properties.getDrivers(), properties.getDriverProfiles(),
                new cn.iocoder.yudao.framework.dbswitch.driver.ConnectionBudget(
                        properties.getPool().getMaximumPhysicalConnections(),
                        properties.getPool().getMetadataReservedConnections()));
    }

    /**
     * 根据当前输入构建并返回 {@code DbSwitchOptions}。
     */
    @Bean
    @ConditionalOnMissingBean
    public DbSwitchOptions dbSwitchOptions(DbSwitchProperties properties) {
        var migration = properties.getMigration();
        var pool = properties.getPool();
        return new DbSwitchOptions(migration.getMaxCacheBytes(), migration.isPipelineWriteEnabled(),
                migration.isOlapStreamLoadEnabled(), migration.getQueryTimeoutSeconds(), pool.getMinimumIdle(),
                pool.getIdleTimeoutMs(), pool.getValidationTimeoutMs(), pool.getLeakDetectionThresholdMs(),
                new cn.iocoder.yudao.framework.dbswitch.common.config.DiffSortLimits(
                        migration.getDiffSortMemoryBytes(), migration.getDiffSortMaxSpillBytes(),
                        migration.getDiffSortDirectory(),
                        migration.getDiffSortMaxSharedSpillBytes()));
    }

    /**
     * 根据当前输入构建并返回 {@code DbSwitchRuntime}。
     */
    @Bean
    @ConditionalOnMissingBean
    public DbSwitchRuntime dbSwitchRuntime(DbSwitchProductRegistry products, DbSwitchOptions options,
            ConfiguredJdbcDrivers drivers, DbSwitchProperties properties) {
        return new DbSwitchRuntime(products, options, drivers,
                new cn.iocoder.yudao.framework.dbswitch.data.query.QueryRuntime.AdmissionLimits(
                        properties.getQuery().getMaximumOperations(),
                        properties.getQuery().getMaximumOperationsPerScope()));
    }
}
