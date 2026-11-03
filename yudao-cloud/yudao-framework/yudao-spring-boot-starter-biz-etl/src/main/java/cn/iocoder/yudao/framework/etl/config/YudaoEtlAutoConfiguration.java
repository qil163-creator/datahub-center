package cn.iocoder.yudao.framework.etl.config;

import cn.iocoder.yudao.framework.etl.core.engine.EtlPipelineEngine;
import cn.iocoder.yudao.framework.etl.core.executor.EtlExecutorRegistry;
import cn.iocoder.yudao.framework.etl.core.schema.EtlSchemaService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 可视化 ETL 引擎自动配置
 *
 * @author Li
 */
@AutoConfiguration(after = cn.iocoder.yudao.framework.dbswitch.config.YudaoDbSwitchAutoConfiguration.class)
@EnableConfigurationProperties(EtlProperties.class)
public class YudaoEtlAutoConfiguration {

    /**
     * 根据当前输入构建并返回 {@code EtlExecutorRegistry}。
     */
    @Bean
    @ConditionalOnMissingBean
    public EtlExecutorRegistry etlExecutorRegistry() {
        return new EtlExecutorRegistry();
    }

    /**
     * 根据当前输入构建并返回 {@code EtlPipelineEngine}。
     */
    @Bean
    @ConditionalOnMissingBean
    public EtlPipelineEngine etlPipelineEngine(EtlExecutorRegistry registry,
            cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime runtime, EtlProperties properties,
            cn.iocoder.yudao.framework.etl.core.script.EtlScriptRunner scriptRunner) {
        return new EtlPipelineEngine(registry, runtime, properties.fileOutputPolicy(), scriptRunner);
    }

    /**
     * 根据当前输入构建并返回 {@code cn.iocoder.yudao.framework.etl.core.script.EtlScriptRunner}。
     */
    @Bean(initMethod = "initializeRecovery")
    @ConditionalOnMissingBean
    public cn.iocoder.yudao.framework.etl.core.script.EtlScriptRunner etlScriptRunner(EtlProperties properties) {
        return new cn.iocoder.yudao.framework.etl.core.script.EtlScriptRunner(properties.scriptWorkerPolicy());
    }

    /**
     * 根据当前输入构建并返回 {@code EtlSchemaService}。
     */
    @Bean
    @ConditionalOnMissingBean
    public EtlSchemaService etlSchemaService(
            cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime runtime) {
        return new EtlSchemaService(runtime);
    }

}
