package cn.iocoder.yudao.framework.dbswitch.dbwriter;

import javax.sql.DataSource;
import java.util.List;

/**
 * 数据库批量写入定义接口
 *
 * @author jrl
 */
public interface IDatabaseWriter {

    /** 纯计划：生成实际批量 INSERT 模板，不取得连接或写入目标。 */
    default String planInsertSql(String schema, String table, List<String> fields) {
        throw new UnsupportedOperationException("Authorized INSERT plans are not implemented by this writer");
    }

    /** 安装外部授权返回的模板；必须在任何目标写入前校验，prepareWrite 和批次仍绑定同一对象及列。 */
    default void useAuthorizedInsertSql(String schema, String table, List<String> fields, String sql,
            List<Integer> parameterOrder) {
        throw new UnsupportedOperationException("Authorized INSERT plans are not implemented by this writer");
    }

    /**
     * 执行目标 DDL 或清空前，检查此操作所需的驱动及服务器前提。
     * 可借用并关闭自动提交连接，以执行有界只读探测；不得改变
     * 目标数据、持有调用方事务或打开写入协议。向后兼容的
     * 默认实现不提供认证。prepareWrite 和每个实际批次仍须校验表。
     */
    default void preflightRuntimeWrite() {
    }

    /**
     * 在 CREATE、替换或清空前检查实际计划的目标定义。这是
     * 纯计划检查：实现不得执行 SQL、获取连接或改变
     * 事务。向后兼容的默认实现不提供操作认证；
     * 准备好的物理目标和每个批次仍须进行常规运行时检查。
     */
    default void preflightTargetDefinition(String schema, String table, List<String> createStatements,
            List<String> writeFields) {
    }

    /** 在替换 DDL 前，对产品原生目标计划执行纯传输专项检查。
     * 结构校验仍由原生计划负责，写入器重新观测创建后的目标。
     * 默认实现不提供认证；此钩子不得借用连接或执行 SQL。
     */
    default void preflightNativeTarget(
            cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTableDefinition definition,
            java.util.Map<String, String> mapping) {
    }

    /**
     * 拒绝当前实现未支持的 {@code configureSequenceTransfer} 操作。
     */
    default void configureSequenceTransfer(SequenceTransferScope.TableBinding binding) {
        throw new UnsupportedOperationException("Shared sequence bindings are not implemented by this writer");
    }

    /**
     * 配置标识列在当前搬运路径中的处理方式。
     */
    default void configureIdentityTransfer(IdentityTransferOptions options) {
        if (!IdentityTransferOptions.disabled().equals(options)) {
            throw new UnsupportedOperationException("Identity transfer is not implemented by this writer");
        }
    }

    /** 在目标破坏性操作前调用；不得猜测实际目标标识语义。 */
    default void preflightIdentityTransfer(String schema, String table, boolean exists) {
    }

    /** 仅在每个行批次均返回已确认结果后调用；失败必须使表任务失败。 */
    default void completeIdentityTransfer() {
    }

    /** false 表示不支持且没有任何副作用。true 表示替换已完成并得到确认
     * 结果。任何异常（包括 UNKNOWN）均不得回退到另一替换路径。 */
    default boolean prepareIdentityReplacement(IdentityReplacement replacement) {
        return false;
    }

    /**
     * prepareWrite 后可选的产品自有清空操作。准入包含物理
     * 代际的写入器可在一个自有事务中校验并锁定目标、清空目标及刷新
     * 其验证依据。仅在确认提交后返回 true。false 承诺没有任何副作用，
     * 并保留现有数据库操作器路径；异常（包括 UNKNOWN）绝不允许回退
     * 或自动重放。实现必须拒绝与已准备对象不同的 schema/table。
     */
    default boolean truncatePreparedTarget(String schema, String table) {
        return false;
    }

    /** prepare/write 前配置操作实例；提供方必须显式接受自定义限额。 */
    default void configureBatchLimits(cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits limits) {
        if (!cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits.defaults().equals(limits)) {
            throw new UnsupportedOperationException("Batch limits are not implemented by this provider");
        }
    }

    /** 使用前在新操作上调用；必须显式支持非默认选项。 */
    default void configure(cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions options) {
        if (!cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions.defaults().equals(options)) {
            throw new UnsupportedOperationException("Runtime options are not implemented by this provider");
        }
    }

    /**
     * 获取数据源对象
     *
     * @return DataSource数据源对象
     */
    DataSource getDataSource();

    /**
     * 批量写入预处理
     *
     * @param schemaName schema名称
     * @param tableName  table名称
     */
    void prepareWrite(String schemaName, String tableName, List<String> fieldNames);

    /**
     * 批量数据写入
     *
     * @param fieldNames   字段名称列表
     * @param recordValues 数据记录
     * @return 返回实际写入的数据记录条数
     */
    long write(List<String> fieldNames, List<Object[]> recordValues);

    /** 可选原生单批提交见证；旧实现继续工作，但 null 不能解释为已取得提交/到数证明。 */
    default BatchWriteResult writeWithReceipt(List<String> fieldNames, List<Object[]> recordValues) {
        long acknowledgedRows = write(fieldNames, recordValues);
        // 提供方已尝试写入；无效计数不属于预检查拒绝。
        if (acknowledgedRows < 0) {
            throw new UnconfirmedWriteException();
        }
        return new BatchWriteResult(acknowledgedRows, null);
    }
}
