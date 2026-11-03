package cn.iocoder.yudao.module.dataforge.enums.fill;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 在线填报-模板工作模式枚举
 *
 * 新任务一律使用审批流模式；遗留直写仅限存量模板在受控开关下短期使用
 *
 * @author Li
 */
@Getter
@AllArgsConstructor
public enum FillWorkflowModeEnum {

    LEGACY_DIRECT(1, "遗留直写（旧链路，直接读写正式物理表）"),
    APPROVAL_FLOW(2, "审批流（草稿-快照-审批-合并，禁止直写正式表）");

    private final Integer mode;

    private final String name;

}
