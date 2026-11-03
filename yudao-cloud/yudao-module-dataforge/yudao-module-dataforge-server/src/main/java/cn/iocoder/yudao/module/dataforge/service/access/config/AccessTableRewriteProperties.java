package cn.iocoder.yudao.module.dataforge.service.access.config;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery.TableRewriteLimits;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/** 独立的部署授权：TD 分组授权绝不隐含允许替换整张表。 */
@Component
@ConfigurationProperties(prefix = "yudao.dataforge.access.table-rewrite")
public class AccessTableRewriteProperties {

    private final AccessGroupRewriteProperties deployment = new AccessGroupRewriteProperties();

    private int maxRows = 100_000, maxPages = 4096;

    private long maxMaterialBytes = 64L * 1024 * 1024;

    /**
     * 判断是否满足启用状态条件。
     *
     * @return 判断结果
     */
    public boolean isEnabled() {
        return deployment.isEnabled();
    }

    /**
     * 设置启用状态。
     *
     * @param value 待处理的值
     */
    public void setEnabled(boolean value) {
        deployment.setEnabled(value);
    }

    /**
     * 读取当前键编号。
     *
     * @return 当前键编号
     */
    public String getCurrentKeyId() {
        return deployment.getCurrentKeyId();
    }

    /**
     * 设置当前键编号。
     *
     * @param value 待处理的值
     */
    public void setCurrentKeyId(String value) {
        deployment.setCurrentKeyId(value);
    }

    /**
     * 读取密钥环。
     *
     * @return 密钥环
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public Map<String, String> getKeyring() {
        return deployment.getKeyring();
    }

    /**
     * 设置密钥环。
     *
     * @param value 待处理的值
     */
    public void setKeyring(Map<String, String> value) {
        deployment.setKeyring(value);
    }

    /**
     * 读取排他任务。
     *
     * @return 排他任务
     */
    public Set<String> getExclusiveTasks() {
        return deployment.getExclusiveTasks();
    }

    /**
     * 设置排他任务。
     *
     * @param value 待处理的值
     */
    public void setExclusiveTasks(Set<String> value) {
        deployment.setExclusiveTasks(value);
    }

    /**
     * 读取控制结构。
     *
     * @return 控制结构
     */
    public String getControlSchema() {
        return deployment.getControlSchema();
    }

    /**
     * 设置控制结构。
     *
     * @param value 待处理的值
     */
    public void setControlSchema(String value) {
        deployment.setControlSchema(value);
    }

    /**
     * 读取事务超时秒数。
     *
     * @return 事务超时秒数
     */
    public int getTransactionTimeoutSeconds() {
        return deployment.getTransactionTimeoutSeconds();
    }

    /**
     * 设置事务超时秒数。
     *
     * @param value 待处理的值
     */
    public void setTransactionTimeoutSeconds(int value) {
        deployment.setTransactionTimeoutSeconds(value);
    }

    /**
     * 返回同步单表重写的最大行数。
     *
     * @return 最大行数
     */
    public int getMaxRows() {
        return maxRows;
    }

    /**
     * 设置同步单表重写的最大行数。
     *
     * @param value 待处理的值
     */
    public void setMaxRows(int value) {
        maxRows = value;
    }

    /**
     * 返回同步单表重写的最大值分页。
     *
     * @return 最大值分页
     */
    public int getMaxPages() {
        return maxPages;
    }

    /**
     * 设置同步单表重写的最大值分页。
     *
     * @param value 待处理的值
     */
    public void setMaxPages(int value) {
        maxPages = value;
    }

    /**
     * 返回同步单表重写的最大值物料字节。
     *
     * @return 最大值物料字节
     */
    public long getMaxMaterialBytes() {
        return maxMaterialBytes;
    }

    /**
     * 设置同步单表重写的最大值物料字节。
     *
     * @param value 待处理的值
     */
    public void setMaxMaterialBytes(long value) {
        maxMaterialBytes = value;
    }

    /**
     * 校验同步单表重写所需的访问权限。
     *
     * @param task 任务记录
     * @return 部署
     */
    public AccessGroupRewriteProperties.Deployment authorize(long task) {
        try {
            return deployment.authorize(task);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("整表替换未获部署级独占授权，或恢复密钥配置无效");
        }
    }

    /**
     * 获取同步单表重写的上限。
     *
     * @param page 分页
     * @param runtimeBudget 运行时预算
     * @return 单表重写上限
     */
    public TableRewriteLimits limits(BatchLimits page, long runtimeBudget) {
        if (maxMaterialBytes > runtimeBudget) {
            throw new IllegalArgumentException("整表恢复材料超过运行时内存预算");
        }
        return new TableRewriteLimits(page, maxRows, maxPages, maxMaterialBytes);
    }

    /**
     * 返回当前对象的字符串表示，供日志或展示使用。
     *
     * @return 当前对象的字符串表示，供日志或展示使用文本
     */
    @Override
    public String toString() {
        return "AccessTableRewriteProperties[enabled=" + isEnabled() + "]";
    }
}
