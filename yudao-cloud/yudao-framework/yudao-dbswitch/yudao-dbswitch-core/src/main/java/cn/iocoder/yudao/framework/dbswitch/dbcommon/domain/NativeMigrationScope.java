package cn.iocoder.yudao.framework.dbswitch.dbcommon.domain;

import cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Relation;

import java.util.Map;
import java.util.Objects;

/** 产品持有的依赖执行；属于可信进程状态，绝不是序列化请求绑定。
 * 作用域不代表跨表事务回滚。表回执在最终处理失败后仍保留。 */
public interface NativeMigrationScope extends AutoCloseable {

    record TablePlan(Relation source, Relation target, Map<String, String> columns, NativeTableDefinition definition) {

        /**
         * 校验构造参数并初始化 {@code TablePlan}。
         */
        public TablePlan {
            Objects.requireNonNull(source);
            Objects.requireNonNull(target);
            columns = Map.copyOf(columns);
            Objects.requireNonNull(definition);
        }
    }

    record Options(boolean structureOnly, boolean replace, boolean truncate, boolean failIfExists,
                   boolean exclusiveDdlOwnership) {

    }

    interface TableBinding {

        /**
         * 根据当前目标定义创建原生对象操作计划。
         */
        NativeTargetPlan targetPlan();

        /** 仅在确认仅结构操作完成，或所有数据批次均确认完成后调用。 */
        void tableCompleted();
    }

    /**
     * 返回绑定信息。
     */
    TableBinding binding(Relation source);

    /** 首个成员执行 DDL 前，完成全部依赖闭包、映射、目标和名称检查。 */
    void prepare();

    /** 所有成员完成后、发布成功前，安装或校验剩余依赖。 */
    void complete();

    /**
     * 关闭 {@code NativeMigrationScope} 当前持有的资源。
     */
    @Override
    void close();
}
