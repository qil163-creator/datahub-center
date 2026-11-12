package cn.iocoder.yudao.framework.dbswitch.core.lineage;

import java.util.Objects;

/**
 * 某个输出值依赖的一个上游物理字段。
 *
 * @param table     上游物理表
 * @param column    上游字段名；{@code *} 表示缺少表结构时保留的虚拟全列
 * @param transform 变换类型
 * @param ambiguous 字段归属无法唯一确定时为 true（例如 JOIN 中未限定的字段）
 * @param masking   经过哈希、脱敏或计数等不暴露原值的变换时为 true
 */
public record LineageColumnSource(LineageTable table, String column, LineageTransform transform,
                                  boolean ambiguous, boolean masking) {

    /**
     * 校验必填属性。
     */
    public LineageColumnSource {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(transform, "transform");
    }

    /**
     * 按外层上下文返回组合后的来源。
     */
    LineageColumnSource under(LineageTransform outer, boolean outerAmbiguous, boolean outerMasking) {
        return new LineageColumnSource(table, column, LineageTransform.compose(outer, transform),
                ambiguous || outerAmbiguous, masking || outerMasking);
    }
}
