package cn.iocoder.yudao.framework.quality.config;

import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlRegistry;
import cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionRegistry;
import cn.iocoder.yudao.framework.quality.core.row.QualityRowEvaluator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 数据质量引擎自动配置
 *
 * @author Li
 */
@AutoConfiguration
public class YudaoQualityAutoConfiguration {

    /**
     * 根据当前输入构建并返回 {@code AuditSqlRegistry}。
     */
    @Bean
    @ConditionalOnMissingBean
    public AuditSqlRegistry auditSqlRegistry() {
        return new AuditSqlRegistry();
    }

    /**
     * 根据当前输入构建并返回 {@code CleanFunctionRegistry}。
     */
    @Bean
    @ConditionalOnMissingBean
    public CleanFunctionRegistry cleanFunctionRegistry() {
        return new CleanFunctionRegistry();
    }

    /**
     * 根据当前输入构建并返回 {@code QualityRowEvaluator}。
     */
    @Bean
    @ConditionalOnMissingBean
    public QualityRowEvaluator qualityRowEvaluator() {
        return new QualityRowEvaluator();
    }

}
