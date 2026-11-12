package cn.iocoder.yudao.module.governance.enums.lineage;

import cn.iocoder.yudao.framework.common.core.ArrayValuable;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 外部数据集种类，仅 EXTERNAL 节点使用。
 *
 * <p>线协议值为枚举常量名（{@link #name()}），新增常量属于契约变更。</p>
 */
@Getter
@AllArgsConstructor
public enum LineageExternalKindEnum implements ArrayValuable<String> {

    KAFKA("Kafka 主题"),
    FILE("文件"),
    HDFS("HDFS 路径"),
    API("接口"),
    MONGODB("MongoDB 集合"),
    OTHER("其他");

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
