package cn.iocoder.yudao.module.modeling.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 模型表类型枚举
 *
 * 与 dh_modeling_dw_layer.default_table_type 取值一致，模型未指定时从所属分层带出默认值。
 *
 * @author Li
 */
@Getter
@AllArgsConstructor
public enum ModelTableTypeEnum {

    SOURCE("SOURCE", "贴源表"),
    DETAIL("DETAIL", "明细表"),
    DIMENSION("DIMENSION", "维度表"),
    SUMMARY("SUMMARY", "汇总表"),
    APPLICATION("APPLICATION", "应用表");

    /**
     * 类型编码
     */
    private final String type;

    /**
     * 类型名称
     */
    private final String name;

    /**
     * 判断输入是否满足当前校验规则。
     *
     * @param type 类型标识
     * @return 判断结果
     */
    public static boolean isValid(String type) {
        return of(type) != null;
    }

    /**
     * 按类型编码查枚举，不存在或为空返回 null
     */
    public static ModelTableTypeEnum of(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        return Arrays.stream(values())
                .filter(item -> item.getType().equals(type))
                .findFirst()
                .orElse(null);
    }

}
