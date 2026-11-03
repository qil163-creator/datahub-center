package cn.iocoder.yudao.module.governance.enums.standard;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 数据标准--标准数据元生命周期状态
 *
 * <p>状态机：DRAFT（草稿）→ PUBLISHED（已发布）→ DEPRECATED（已废止）。</p>
 *
 * @author Li
 */
@Getter
@AllArgsConstructor
public enum StandardElementLifecycleEnum {

    /** 草稿：可编辑、可删除，不建议对外引用 */
    DRAFT("DRAFT"),
    /** 已发布：正式生效，可被落标引用，不可直接删除 */
    PUBLISHED("PUBLISHED"),
    /** 已废止：只读，不可再绑定与落标 */
    DEPRECATED("DEPRECATED");

    private final String status;

    /**
     * 按生命周期编码查找标准数据元状态枚举。
     */
    public static StandardElementLifecycleEnum of(String status) {
        for (StandardElementLifecycleEnum value : values()) {
            if (value.status.equals(status)) {
                return value;
            }
        }
        return null;
    }

}
