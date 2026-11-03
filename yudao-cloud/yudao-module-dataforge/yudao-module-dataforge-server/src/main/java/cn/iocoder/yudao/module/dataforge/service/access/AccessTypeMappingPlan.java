package cn.iocoder.yudao.module.dataforge.service.access;

import java.util.List;

/** 有界、仅含元数据的运行诊断。业务记录独立于组件对象和 HTTP VO。 */
public record AccessTypeMappingPlan(int schemaVersion,
                                    String sourceProduct, String sourceSchema, String sourceTable,
                                    String targetProduct, String targetSchema, String targetTable,
                                    String fidelity, boolean accepted, int totalColumns, boolean truncated,
                                    List<Column> columns) {

    /**
     * 创建访问类型映射计划实例。
     *
     * @param schemaVersion 结构版本
     * @param sourceProduct 来源产品
     * @param sourceSchema 来源结构
     * @param sourceTable 来源表
     * @param targetProduct 目标产品
     * @param targetSchema 目标结构
     * @param targetTable 目标表
     * @param fidelity 保真
     * @param accepted 已受理
     * @param totalColumns 总量列集合
     * @param truncated 截断
     * @param columns 列集合
     */
    public AccessTypeMappingPlan {
        if (schemaVersion != 1 || columns == null || columns.size() > 4096 || totalColumns < columns.size()
                || !truncated && totalColumns != columns.size()) {
            throw new IllegalArgumentException("类型映射诊断版本或范围无效");
        }
        columns = List.copyOf(columns);
    }

    public record Column(String column, String sourceNativeType, String logicalType, String targetNativeType,
                         String fidelity, boolean accepted, String mappingFingerprint, List<String> lossCodes,
                         List<Issue> issues) {

        /**
         * 创建列实例。
         *
         * @param column 列
         * @param sourceNativeType 来源原生类型
         * @param logicalType 逻辑类型
         * @param targetNativeType 目标原生类型
         * @param fidelity 保真
         * @param accepted 已受理
         * @param mappingFingerprint 映射指纹
         * @param lossCodes 损失编码
         * @param issues 问题
         */
        public Column {
            lossCodes = List.copyOf(lossCodes);
            issues = List.copyOf(issues);
        }
    }

    public record Issue(String severity, String code, String detail) {

    }
}
