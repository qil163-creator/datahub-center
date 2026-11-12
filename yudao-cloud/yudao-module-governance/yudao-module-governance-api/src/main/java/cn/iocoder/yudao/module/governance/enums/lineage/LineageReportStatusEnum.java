package cn.iocoder.yudao.module.governance.enums.lineage;

import cn.iocoder.yudao.framework.common.core.ArrayValuable;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 血缘上报状态：解析结果状态；REMOVED 表示来源已删除或停用，治理侧清空其全部边。
 *
 * <p>线协议值为枚举常量名（{@link #name()}），新增常量属于契约变更。</p>
 */
@Getter
@AllArgsConstructor
public enum LineageReportStatusEnum implements ArrayValuable<String> {

    SUCCESS("解析成功"),
    PARTIAL("部分解析"),
    FAILED("解析失败"),
    UNSUPPORTED("不支持解析"),
    REMOVED("来源已移除");

    public static final String[] ARRAYS = Arrays.stream(values()).map(Enum::name).toArray(String[]::new);

    /**
     * 中文说明。
     */
    private final String label;

    /**
     * 判断字符串是否为合法取值（大小写敏感）。
     */
    public static boolean isValid(String value) {
        return value != null && Arrays.asList(ARRAYS).contains(value);
    }

    @Override
    public String[] array() {
        return ARRAYS;
    }

}
