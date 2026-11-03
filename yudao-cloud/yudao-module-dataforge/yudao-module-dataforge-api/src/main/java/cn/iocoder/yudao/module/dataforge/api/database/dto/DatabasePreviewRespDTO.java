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

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Column {

        private String name;

        private String label;

        private String typeName;

    }

}
