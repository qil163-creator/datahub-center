package cn.iocoder.yudao.framework.dbswitch.dbcommon.domain;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

import java.util.List;
import java.util.Objects;

/** 不可变产品原生结构依据；format/document 仅由对应产品解释。 */
public record NativeTableDefinition(ProductTypeEnum product, String format, String document, List<String> fields) {

    /**
     * 校验构造参数并初始化 {@code NativeTableDefinition}。
     */
    public NativeTableDefinition {
        Objects.requireNonNull(product);
        Objects.requireNonNull(format);
        Objects.requireNonNull(document);
        fields = List.copyOf(fields);
        if (format.isBlank() || document.isBlank() || fields.isEmpty()) {
            throw new IllegalArgumentException("Native structure evidence is incomplete");
        }
    }

    /**
     * 返回当前对象的文本表示。
     */
    @Override
    public String toString() {
        return "NativeTableDefinition[product=" + product + ", format=" + format + ", fields=" + fields.size() + "]";
    }
}
