package cn.iocoder.yudao.framework.dbswitch.dbsynch;

import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeRecoveryResource;

import java.util.List;

/** 延迟执行的整表 DIFF。暂存不等于写入确认；seal 先持久化完整
 * 材料，execute 才可改变目标。比对仍由共享计算器负责。 */
public interface WholeTableDifference {

    enum Change {INSERT, UPDATE, DELETE}

    /**
     * 准备当前阶段的数据或目标状态。
     */
    void stage(Change change, List<Object[]> records);

    /**
     * 对当前恢复材料进行封装或加密并返回可保存的载荷。
     */
    Prepared seal();

    /**
     * 返回当前操作恢复时需要关联的资源信息。
     */
    default List<NativeRecoveryResource> recoveryResources() {
        return List.of();
    }

    interface Prepared {

        /**
         * 返回当前操作影响的数据或结构变更数量。
         */
        long changeCount();

        /**
         * 根据当前内容计算摘要，供一致性比较使用。
         */
        String digest();

        /** 仅在精确完整目标回读和 APPLIED 后返回已确认的逻辑变更。 */
        long execute();
    }
}
