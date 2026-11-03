package cn.iocoder.yudao.module.dataforge.controller.admin.access.vo;

import cn.iocoder.yudao.module.dataforge.controller.admin.dataapi.vo.DataApiSaveReqVO;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - API 接入映射（一个 API → 一张目标表）")
@Data
public class ApiAccessMappingVO {

    @Schema(description = "本任务独立保存的接口请求与返回字段配置")
    @Valid
    @NotNull(message = "接口配置不能为空")
    @lombok.ToString.Exclude
    private DataApiSaveReqVO apiConfig;

    @Schema(description = "源 API 名称（冗余展示）", example = "人口基础信息接口")
    private String sourceApiName;

    @Schema(description = "目标表名", example = "ods_population")
    private String targetTable;

    @Schema(description = "字段映射")
    private List<ApiColumnMapVO> columnMaps;

    /** 增量写入必须完整导入接口声明的主键，不能静默退化为清表全量。 */
    public boolean hasCompletePrimaryKeyMapping() {
        if (apiConfig == null || apiConfig.getDataColumns() == null || columnMaps == null) {
            return false;
        }
        var primaryKeys = new java.util.HashSet<String>();
        for (var column : apiConfig.getDataColumns()) {
            if (column != null && Boolean.TRUE.equals(column.getPk())) {
                if (column.getFieldName() == null || column.getFieldName().isBlank()
                        || !primaryKeys.add(column.getFieldName())) {
                    return false;
                }
            }
        }
        if (primaryKeys.isEmpty()) {
            return false;
        }
        var importedSources = new java.util.HashSet<String>();
        var importedTargets = new java.util.HashSet<String>();
        for (var mapping : columnMaps) {
            if (mapping != null && Integer.valueOf(1).equals(mapping.getImportFlg())) {
                if (mapping.getSourceColumn() == null || mapping.getSourceColumn().isBlank()
                        || mapping.getTargetColumn() == null || mapping.getTargetColumn().isBlank()
                        || !importedSources.add(mapping.getSourceColumn()) || !importedTargets.add(
                        mapping.getTargetColumn())) {
                    return false;
                }
            }
        }
        return importedSources.containsAll(primaryKeys);
    }

    @Schema(description = "字段映射项")
    @Data
    public static class ApiColumnMapVO {

        @Schema(description = "API 字段名", example = "idCard")
        private String sourceColumn;

        @Schema(description = "目标表字段名", example = "id_card")
        private String targetColumn;

        @Schema(description = "是否导入：0-否 1-是", example = "1")
        private Integer importFlg;

    }

}
