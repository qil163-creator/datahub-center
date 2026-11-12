package cn.iocoder.yudao.module.dataforge.api.database.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Schema(description = "RPC 服务 - 数据表只读预览结果")
@Data
public class DatabasePreviewRespDTO {

    private List<Column> columns;

    private List<Map<String, Object>> rows;

    private Boolean truncated;

    @Schema(description = "被动态脱敏的列；为空表示没有列被脱敏")
    private List<MaskedColumn> maskedColumns = new java.util.ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Column {

        private String name;

        private String label;

        private String typeName;

    }

    /** 被动态脱敏的列。原值不会出现在 rows 中。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MaskedColumn {

        @Schema(description = "列名（rows 中的键）")
        private String name;

        @Schema(description = "原因：RULE-命中分类规则 UNCLASSIFIED-敏感未分类 UNTRACEABLE-来源无法追溯 UNVERIFIED-策略无法核验")
        private String reason;

        @Schema(description = "命中的分类名称")
        private List<String> categoryNames;

    }

}
