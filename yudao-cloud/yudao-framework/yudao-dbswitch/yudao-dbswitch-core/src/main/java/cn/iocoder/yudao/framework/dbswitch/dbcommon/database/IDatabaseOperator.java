package cn.iocoder.yudao.framework.dbswitch.dbcommon.database;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.PrepareStatementResultSet;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.StatementResultSet;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * 数据库操作器接口定义
 *
 * @author jrl
 */
public interface IDatabaseOperator {

    /**
     * 判断是否支持原生迁移范围。
     */
    default boolean supportsNativeMigrationScope() {
        return false;
    }

    /**
     * 准备原生迁移范围。
     */
    default java.util.Optional<cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeMigrationScope> prepareNativeMigrationScope(
            List<cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeMigrationScope.TablePlan> tables,
            cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeMigrationScope.Options options) {
        return java.util.Optional.empty();
    }

    /**
     * 准备序列值迁移所需的资源和状态。
     */
    default cn.iocoder.yudao.framework.dbswitch.dbwriter.SequenceTransferScope prepareSequenceTransfer(
            cn.iocoder.yudao.framework.dbswitch.core.model.SequenceDependencyGraph graph,
            List<cn.iocoder.yudao.framework.dbswitch.dbwriter.SequenceTransferScope.TablePlan> tables,
            boolean replace, boolean truncate, boolean failIfExists) {
        throw new UnsupportedOperationException("Shared sequence transfer is not implemented by this product");
    }

    /**
     * 准备序列值迁移所需的资源和状态。
     */
    default cn.iocoder.yudao.framework.dbswitch.dbwriter.SequenceTransferScope prepareSequenceTransfer(
            cn.iocoder.yudao.framework.dbswitch.core.model.SequenceDependencyGraph graph,
            List<cn.iocoder.yudao.framework.dbswitch.dbwriter.SequenceTransferScope.TablePlan> tables,
            boolean replace, boolean truncate, boolean failIfExists,
            cn.iocoder.yudao.framework.dbswitch.dbwriter.SequenceTransferScope.Mode mode) {
        if (mode != cn.iocoder.yudao.framework.dbswitch.dbwriter.SequenceTransferScope.Mode.DATA_AND_STRUCTURE) {
            throw new UnsupportedOperationException(
                    "Shared sequence structure-only planning is not implemented by this product");
        }
        return prepareSequenceTransfer(graph, tables, replace, truncate, failIfExists);
    }

    /** 只读依赖发现；不执行结构变更，也不扩展调用方选择范围。 */
    default cn.iocoder.yudao.framework.dbswitch.core.model.SequenceDependencyGraph planMigrationDependencies(
            List<cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Relation> selectedRelations) {
        throw new UnsupportedOperationException("Sequence dependency discovery is not implemented by this product");
    }

    /**
     * 只读展开所选原生关系族，形成互不重复的物理迁移单元。
     * 产品必须保留显式选择，将每个返回单元绑定到其 schema，限制目录
     * 读取范围并拒绝有歧义的覆盖。此钩子不授予访问权限，也不执行目标 DDL。
     * 迁移协调器在调度前重新应用排除规则并校验全部结果。
     */
    default List<cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription> planMigrationSources(
            List<cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription> selected) {
        return List.copyOf(selected);
    }

    /**
     * 可选的产品原生迁移框架。空值表示普通的已观测关系列。
     * 这些列描述显式传输协议，绝不是通过采样得到的文档结构。
     */
    default List<ColumnDescription> nativeMigrationColumns(String schemaName, String tableName) {
        return List.of();
    }

    /** 原生索引及集合结构支持独立于关系型 SQL DDL。 */
    default boolean supportsNativeTableDefinition() {
        return false;
    }

    /**
     * 读取数据库原生表定义及其元数据证据。
     */
    default cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTableDefinition describeNativeTable(
            String schemaName, String tableName) {
        throw new UnsupportedOperationException("Native table definitions are not implemented by this product");
    }

    /**
     * 为当前产品准备原生目标对象及写入计划。
     */
    default cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTargetPlan prepareNativeTarget(
            cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTableDefinition source,
            String schemaName, String tableName, Map<String, String> fieldMapping, boolean replace) {
        throw new UnsupportedOperationException("Native target planning is not implemented by this product");
    }

    /** Optional table layout and comment overrides; unsupported products fail before target mutation. */
    default cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTargetPlan prepareNativeTarget(
            cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTableDefinition source,
            String schemaName, String tableName, Map<String, String> fieldMapping, boolean replace,
            String targetComment, cn.iocoder.yudao.framework.dbswitch.core.model.DorisCreateTableOptions dorisOptions) {
        if (targetComment != null || dorisOptions != null) {
            throw new UnsupportedOperationException("Native table overrides are not implemented by this product");
        }
        return prepareNativeTarget(source, schemaName, tableName, fieldMapping, replace);
    }

    /** A persisted layout request must match an existing target; this never changes an existing table. */
    default void validateExistingTableOptions(String schemaName, String tableName, String targetComment,
            cn.iocoder.yudao.framework.dbswitch.core.model.DorisCreateTableOptions dorisOptions) {
        if (targetComment != null || dorisOptions != null) {
            throw new UnsupportedOperationException("Existing table options are not implemented by this product");
        }
    }

    /**
     * 以只读方式规划现有关系的产品原生结构变更，并保留数据。
     * 预期定义是原生验证依据，绝不是关系型 SQL。不支持的变更必须
     * 在修改前失败。返回的单次使用计划绑定已观测的前提，并要求
     * 经校验的回读；调用方必须遵守其排他 DDL 所有权及 UNKNOWN 恢复契约。
     */
    default cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTargetPlan prepareNativeSchemaEvolution(
            cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTableDefinition desired,
            String schemaName, String tableName) {
        throw new UnsupportedOperationException("Native schema evolution is not implemented by this product");
    }

    /**
     * 创建并打开按键推进的读取游标。
     */
    default cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadSession openCursor(
            cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest request) {
        throw new UnsupportedOperationException("Typed cursor reads are not implemented by this product");
    }

    /** 使用前在新操作上调用；必须显式支持非默认选项。 */
    default void configure(cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions options) {
        if (!cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions.defaults().equals(options)) {
            throw new UnsupportedOperationException("Runtime options are not implemented by this provider");
        }
    }

    /**
     * 返回当前实现对应的数据库产品类型。
     */
    ProductTypeEnum getDatabaseType();

    /**
     * 获取数据源
     *
     * @return 数据源
     */
    DataSource getDataSource();

    /**
     * 获取读取(fetch)数据的批次大小
     *
     * @return 批次大小
     */
    int getFetchSize();

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    StatementResultSet queryTableData(String sql, List<String> fields,
            List<String> orders);

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    StatementResultSet queryTableData(String sql);

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    StatementResultSet queryTableData(String sql, List<String> fields);

    /**
     * 设置读取(fetch)数据的批次大小
     *
     * @param size 批次大小
     */
    void setFetchSize(int size);

    /**
     * 生成查询指定字段的select查询SQL语句
     *
     * @param schemaName 模式名称
     * @param tableName  表名称
     * @param fields     字段列表
     * @return 查询指定字段的select查询SQL语句
     */
    String getSelectTableSql(String schemaName, String tableName, List<String> fields);

    /**
     * 获取指定schema下表的按主键有序的结果集
     *
     * @param schemaName 模式名称
     * @param tableName  表名称
     * @param fields     字段列表
     * @param orders     排序字段列表
     * @return 结果集包装对象
     */
    StatementResultSet queryTableData(String schemaName, String tableName, List<String> fields,
            List<String> orders);

    /**
     * 获取指定schema下表的结果集
     *
     * @param schemaName 模式名称
     * @param tableName  表名称
     * @param fields     字段列表
     * @return 结果集包装对象
     */
    StatementResultSet queryTableData(String schemaName, String tableName, List<String> fields);

    /**
     * 清除指定表的所有数据
     *
     * @param schemaName 模式名称
     * @param tableName  表名称
     */
    void truncateTableData(String schemaName, String tableName);

    /**
     * 删除指定物理表
     *
     * @param schemaName 模式名称
     * @param tableName  表名称
     */
    void dropTable(String schemaName, String tableName);

    /**
     * 按增量边界读取表中的新增或变更数据。
     */
    PrepareStatementResultSet queryIncreaseTableData(String sourceSchemaName, String sourceTableName,
            List<String> queryFieldColumn, ColumnDescription increaseColumn, String startVal, String endVal);

    /**
     * 按增量边界读取表中的新增或变更数据。
     */
    PrepareStatementResultSet queryIncreaseTableData(String sql, List<String> queryFieldColumn,
            ColumnDescription increaseColumn, String startVal, String endVal);

    /**
     * 返回当前字段是否属于主键。
     */
    Boolean getPkExist(String targetSchemaName, String targetTableName, Map<String, Object> pkVal) throws SQLException;


}
