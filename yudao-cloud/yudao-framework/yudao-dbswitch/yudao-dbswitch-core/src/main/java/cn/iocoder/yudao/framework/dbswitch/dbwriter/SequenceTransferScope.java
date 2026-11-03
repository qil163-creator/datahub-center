package cn.iocoder.yudao.framework.dbswitch.dbwriter;

import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/** 进程内资源所有者。它绝不是迁移请求或可序列化绑定的一部分。 */
public interface SequenceTransferScope extends AutoCloseable {

    enum Mode {DATA_AND_STRUCTURE, STRUCTURE_ONLY}

    record TablePlan(TableReadPlan.Relation source, TableReadPlan.Relation target, Map<String, String> columns) {

        /**
         * 校验构造参数并初始化 {@code TablePlan}。
         */
        public TablePlan {
            java.util.Objects.requireNonNull(source);
            java.util.Objects.requireNonNull(target);
            columns = Map.copyOf(columns);
        }
    }

    interface TableBinding {

        /**
         * 判断当前路径是否负责写入指定列。
         */
        boolean managesColumn(String targetColumn);

        /**
         * 组装建表操作使用的列定义。
         */
        List<ColumnDescription> columnsForCreate(List<ColumnDescription> columns);

        /**
         * 返回标识列关联的序列名称映射。
         */
        default Map<String, TableReadPlan.Relation> identitySequenceNames() {
            return Map.of();
        }

        /**
         * 说明当前插入是否允许覆盖系统生成的列值。
         */
        default boolean overrideSystemValue() {
            return false;
        }

        /**
         * 返回当前产品需要的默认语句集合。
         */
        List<String> defaultStatements();

        /**
         * 准备写入器。
         */
        void prepareWriter(String schema, String table, List<String> fields);

        /**
         * 在写入下一批数据前检查当前连接和目标状态。
         */
        void beforeBatch(Connection connection) throws SQLException;

        /**
         * 汇总当前表处理完成后的统计和结果。
         */
        void tableCompleted();
    }

    /**
     * 返回绑定信息。
     */
    TableBinding binding(TableReadPlan.Relation source);

    /** 在启动单个自有 PostgreSQL 结构事务前，预编译所有语句。 */
    void prepare(Map<TableReadPlan.Relation, List<String>> createStatements);

    /** 所有成员必须先完成。数据模式对齐序列状态；结构模式只确认已准备 DDL。 */
    void complete();

    /**
     * 关闭 {@code SequenceTransferScope} 当前持有的资源。
     */
    @Override
    void close();
}
