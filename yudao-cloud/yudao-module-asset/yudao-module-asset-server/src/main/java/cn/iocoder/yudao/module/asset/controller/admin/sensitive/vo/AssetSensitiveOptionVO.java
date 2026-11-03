package cn.iocoder.yudao.module.asset.controller.admin.sensitive.vo;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "管理后台 - 敏感清单选项")
public final class AssetSensitiveOptionVO {

    /**
     * 限制资产敏感字段选项工具类的直接实例化。
     */
    private AssetSensitiveOptionVO() {
    }

    public record Category(String key, String parentKey, String type, Long categoryId, String name,
                           boolean selectable, Integer status, String levelName, String levelColor, String ruleName) {

    }

    public record Asset(Long id, String assetName, String tableName, String description, Integer assetType) {

    }

    public record Column(Long id, String columnName, String columnComment, String dataType) {

    }

    public record Deleted(java.util.List<Long> deletedIds, java.util.List<Long> skippedIds) {

    }
}
