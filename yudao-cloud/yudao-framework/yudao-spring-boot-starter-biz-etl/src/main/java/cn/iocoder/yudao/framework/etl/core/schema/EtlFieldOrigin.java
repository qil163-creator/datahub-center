package cn.iocoder.yudao.framework.etl.core.schema;

import cn.iocoder.yudao.framework.dbswitch.core.lineage.LineageTransform;

import java.util.Objects;

/**
 * ETL 字段的一个来源：某个无上游的输入节点上的字段。
 * <p>
 * 只描述画布内的追溯关系；输入节点对应的物理表或外部数据源由调用方根据节点配置解析。
 *
 * @param nodeNo    输入节点编号
 * @param field     输入节点输出的字段名
 * @param transform 从来源到当前字段经过的变换（取路径上最强的 DIRECT 变换）
 * @param masking   路径上是否经过加密或脱敏
 */
public record EtlFieldOrigin(String nodeNo, String field, LineageTransform transform, boolean masking) {

    /**
     * 校验必填属性。
     */
    public EtlFieldOrigin {
        Objects.requireNonNull(nodeNo, "nodeNo");
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(transform, "transform");
    }

    /**
     * 叠加一次变换。
     */
    EtlFieldOrigin under(LineageTransform outer, boolean mask) {
        return new EtlFieldOrigin(nodeNo, field, LineageTransform.compose(outer, transform), masking || mask);
    }
}
