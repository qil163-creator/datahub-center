package cn.iocoder.yudao.module.modeling.api.indicator.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RPC - 指标数据查询结果。指标值以十进制字符串传输；维度值已按查看主体动态脱敏。
 */
@Schema(description = "RPC 服务 - 指标数据查询结果")
@Data
public class IndicatorQueryApiRespDTO {

    @Schema(description = "结果列")
    private List<Column> columns = new ArrayList<>();

    @Schema(description = "结果行：列键 → 十进制字符串或维度值")
    private List<Map<String, String>> rows = new ArrayList<>();

    @Schema(description = "是否因行数上限截断")
    private Boolean truncated;

    @Schema(description = "被动态脱敏的列键")
    private List<String> maskedColumns = new ArrayList<>();

    /** 结果列。 */
    @Data
    public static class Column {

        @Schema(description = "列键（行中的键）")
        private String key;

        @Schema(description = "列名")
        private String title;

        @Schema(description = "类别：DIMENSION/TIME/INDICATOR")
        private String kind;
    }
}
