package cn.iocoder.yudao.module.governance.service.profile;

/**
 * 探查字段的敏感性依据：元数据字段/表上的敏感标注、安全分级，以及已确认的敏感字段打标。
 *
 * @param metaTableId         元数据表编号
 * @param metaColumnId        元数据字段编号
 * @param datasourceId        数据源编号
 * @param schemaName          物理 schema
 * @param tableName           物理表名
 * @param columnName          物理字段名
 * @param columnSensitiveFlag 字段 sensitiveFlag = 1
 * @param safetyLevelId       字段安全分级编号
 * @param tableSensitiveFlag  表 sensitiveFlag = 1
 * @param confirmedTag        存在已确认（CONFIRMED）的敏感字段打标
 * @param confirmedTagLevelId 已确认打标的分级编号
 * @param unresolved          敏感信息读取失败（按敏感处理）
 * @author Li
 */
public record ProfileColumnSensitivity(Long metaTableId, Long metaColumnId, Long datasourceId, String schemaName,
                                       String tableName, String columnName, boolean columnSensitiveFlag,
                                       Long safetyLevelId, boolean tableSensitiveFlag, boolean confirmedTag,
                                       Long confirmedTagLevelId, boolean unresolved) {

    /**
     * 只要任一依据成立或依据读取失败即视为敏感（失败关闭）。
     */
    public boolean sensitive() {
        return unresolved || columnSensitiveFlag || safetyLevelId != null || tableSensitiveFlag || confirmedTag;
    }

}
