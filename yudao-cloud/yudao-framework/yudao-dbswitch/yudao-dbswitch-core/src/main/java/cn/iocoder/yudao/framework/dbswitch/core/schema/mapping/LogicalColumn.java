package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

import java.util.Objects;

/** 目标方向的输入：逻辑值域及影响目标语义的列事实。 */
public record LogicalColumn(String name, LogicalType type, boolean nullable, boolean key,
                            ProductTypeEnum sourceProduct, String sourceNativeType) {

    /**
     * 校验构造参数并初始化 {@code LogicalColumn}。
     */
    public LogicalColumn {
        Objects.requireNonNull(name);
        Objects.requireNonNull(type);
        Objects.requireNonNull(sourceProduct);
        Objects.requireNonNull(sourceNativeType);
    }
}
