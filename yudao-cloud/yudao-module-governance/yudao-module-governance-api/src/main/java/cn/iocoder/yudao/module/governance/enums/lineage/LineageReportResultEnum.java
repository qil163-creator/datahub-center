package cn.iocoder.yudao.module.governance.enums.lineage;

import cn.iocoder.yudao.framework.common.core.ArrayValuable;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 血缘上报处理结果。
 *
 * <p>线协议值为枚举常量名（{@link #name()}），新增常量属于契约变更。</p>
 */
@Getter
@AllArgsConstructor
public enum LineageReportResultEnum implements ArrayValuable<String> {

    ACCEPTED("已接受并整体替换"),
    NOOP("修订与内容未变化，未改动"),
    STALE("修订落后于已存值，未改动");

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
