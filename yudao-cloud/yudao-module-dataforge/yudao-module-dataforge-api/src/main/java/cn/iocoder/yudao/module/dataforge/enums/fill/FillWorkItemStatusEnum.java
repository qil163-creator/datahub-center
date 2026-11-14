package cn.iocoder.yudao.module.dataforge.enums.fill;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Set;

/**
 * 在线填报-组织填报实例（工作项）主状态枚举
 *
 * 状态迁移一律通过工作项行上的数据库 CAS 完成，与 DDL 注释保持同步
 *
 * @author Li
 */
@Getter
@AllArgsConstructor
public enum FillWorkItemStatusEnum {

    PENDING(0, "待填报"),
    FILLING(1, "填报中"),
    SUBMIT_VALIDATING(2, "提交校验中"),
    WAIT_BPM_START(3, "待启动审批"),
    APPROVING(4, "审批中"),
    REWORKING(5, "整改中"),
    WAIT_MERGE(6, "待合并"),
    MERGING(7, "合并中"),
    MERGE_FAILED(8, "合并失败"),
    MERGED(9, "已合并"),
    CANCELLED(10, "已取消"),
    /** 下级数据经上级单位审核通过，随上级一起上报，不单独写入正式表 */
    ROLLED_UP(11, "已汇入上级");

    private final Integer status;

    private final String name;

    /** 允许编辑草稿的状态 */
    public static final Set<Integer> EDITABLE_STATUSES = Set.of(
            PENDING.status, FILLING.status, REWORKING.status);

    /** 允许调整填报人（新增/移除/转派/重新分配）的状态 */
    public static final Set<Integer> ASSIGNABLE_STATUSES = Set.of(
            PENDING.status, FILLING.status, REWORKING.status);

    /** 允许发起提交的状态 */
    public static final Set<Integer> SUBMITTABLE_STATUSES = Set.of(
            PENDING.status, FILLING.status, REWORKING.status);

    /** 已结束：不再需要处理（顶层已合并、下级已汇入上级、已取消） */
    public static final Set<Integer> FINISHED_STATUSES = Set.of(
            MERGED.status, ROLLED_UP.status, CANCELLED.status);

    /** 禁止普通撤回/取消的状态（合并链路已开始） */
    public static final Set<Integer> MERGE_PIPELINE_STATUSES = Set.of(
            WAIT_MERGE.status, MERGING.status, MERGE_FAILED.status, MERGED.status);

    /**
     * 根据输入构造填报工作项状态。
     *
     * @param status 目标状态
     * @return 填报工作项状态
     */
    public static FillWorkItemStatusEnum of(Integer status) {
        return Arrays.stream(values())
                .filter(item -> item.getStatus().equals(status))
                .findFirst().orElse(null);
    }

}
