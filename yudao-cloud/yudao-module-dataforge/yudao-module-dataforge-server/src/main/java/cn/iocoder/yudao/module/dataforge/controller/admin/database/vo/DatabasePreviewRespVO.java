package cn.iocoder.yudao.module.dataforge.controller.admin.database.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Schema(description = "管理后台 - 数据源表样例预览 Response VO")
@Data
public class DatabasePreviewRespVO {

    @Schema(description = "列")
    private List<Column> columns;

    @Schema(description = "行数据")
    private List<Map<String, Object>> rows;

    @Schema(description = "是否因行数上限截断")
    private Boolean truncated;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Column {

        @Schema(description = "列键")
        private String name;

        @Schema(description = "列名")
        private String label;

        @Schema(description = "类型名")
        private String typeName;

    }

}
