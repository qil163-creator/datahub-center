package cn.iocoder.yudao.module.asset.controller.admin.dataquery.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Schema(description = "管理后台 - 数据查询结果 Response VO")
@Data
public class DataQueryResultRespVO {

    @Schema(description = "结果列")
    private List<Column> columns;

    @Schema(description = "结果行")
    private List<Map<String, Object>> rows;

    @Schema(description = "是否因超过最大行数被截断")
    private Boolean truncated;

    @Schema(description = "执行耗时（毫秒）", example = "35")
    private Long elapsedMs;

    @Schema(description = "查询结果列")
    @Data
    public static class Column {

        @Schema(description = "结果集内唯一键（重名列自动追加序号）")
        private String name;

        @Schema(description = "列展示名")
        private String label;

        @Schema(description = "数据库类型名")
        private String typeName;

    }

}
