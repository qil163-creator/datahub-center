package cn.iocoder.yudao.framework.dbswitch.dbsynch;

import javax.sql.DataSource;
import java.util.List;

/**
 * 数据同步接口定义
 *
 * @author jrl
 */
public interface IDatabaseSynchronize {

    /**
     * 拒绝当前实现未支持的 {@code bindTableRewriteProvider} 操作。
     */
    default void bindTableRewriteProvider(
            cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery.TableRewriteBindingProvider provider) {
        if (provider != null) {
            throw new UnsupportedOperationException(
                    "Durable whole-table recovery is not implemented by this synchronizer");
        }
    }

    /** 对于要求完整且持久准备好差异映像的产品，仅在 prepare 后为非 null。 */
    default WholeTableDifference wholeTableDifference() {
        return null;
    }

    /** 即使已准入的管理探测之后 prepare 失败，仍保留精确原生资源。 */
    default List<cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeRecoveryResource> recoveryResources() {
        return List.of();
    }

    /** 显式恢复已认证且已认领的材料；绝不重新读取或比对源。 */
    default void reconcileTableRewrite(cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery.TableRewriteBinding binding,
            cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery.TableRewriteRecord record) {
        throw new UnsupportedOperationException(
                "Whole-table material recovery is not implemented by this synchronizer");
    }

    /** 仅供可信运行时使用的恢复绑定。不支持的产品不得静默忽略。 */
    default void bindGroupRewrite(cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery.GroupRewriteBinding binding) {
        if (binding != null) {
            throw new UnsupportedOperationException("Durable group rewriting is not implemented by this synchronizer");
        }
    }

    /**
     * 拒绝当前实现未支持的 {@code bindGroupRewriteProvider} 操作。
     */
    default void bindGroupRewriteProvider(
            cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery.GroupRewriteBindingProvider provider) {
        if (provider != null) {
            throw new UnsupportedOperationException(
                    "Durable group recovery admission is not implemented by this synchronizer");
        }
    }

    /**
     * 产品可以支持显式匹配键，而无需原生 UNIQUE 约束。其 prepare 必须
     * 校验表模型和当前键值。共享比对器还会在任何回调前检查两个完整
     * 输入快照中的 NULL 及重复键。调用方仍须保证目标的排他
     * 所有权；此标记不保证唯一性或并发写入安全。
     */
    default boolean supportsUnconstrainedMatchKeys() {
        return false;
    }

    /** 在 prepare 前配置新操作；自定义选项不得被静默忽略。 */
    default void configure(cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions options) {
        if (!cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions.defaults().equals(options)) {
            throw new UnsupportedOperationException("Runtime options are not implemented by this synchronizer");
        }
    }

    /**
     * 设置写入批次的行数、字节数及绑定参数限制。
     */
    default void configureBatchLimits(cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits limits) {
        if (!cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits.defaults().equals(limits)) {
            throw new UnsupportedOperationException("Batch limits are not implemented by this provider");
        }
    }

    /**
     * 获取数据源对象
     *
     * @return DataSource数据源对象
     */
    DataSource getDataSource();

    /**
     * 批量Insert/Update/Delete预处理
     *
     * @param schemaName schema名称
     * @param tableName  table名称
     * @param fieldNames 字段列表
     * @param pks        主键字段列表
     */
    void prepare(String schemaName, String tableName, List<String> fieldNames, List<String> pks);


    /**
     * 批量数据Insert
     *
     * @param records 数据记录
     * @return 返回实际影响的记录条数
     */
    long executeInsert(List<Object[]> records);

    /**
     * 批量数据Update
     *
     * @param records 数据记录
     * @return 返回实际影响的记录条数
     */
    long executeUpdate(List<Object[]> records);

    /**
     * 批量数据Delete
     *
     * @param records 数据记录
     * @return 返回实际影响的记录条数
     */
    long executeDelete(List<Object[]> records);
}
