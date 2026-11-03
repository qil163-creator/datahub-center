package cn.iocoder.yudao.module.dataforge.enums.fill;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 在线填报-报送类型枚举
 *
 * @author Li
 */
@Getter
@AllArgsConstructor
public enum FillSubmissionTypeEnum {

    NORMAL(1, "正常报送"),
    EMPTY(2, "零上报（本期无数据，显式确认）"),
    CORRECTION(3, "数据更正");

    private final Integer type;

    private final String name;

}
