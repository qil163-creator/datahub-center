package cn.iocoder.yudao.module.dataforge.api.database.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Schema(description = "RPC 服务 - 数据源只读即席查询请求")
@Data
public class DatabaseQueryReqDTO {

    @NotNull(message = "数据源编号不能为空")
    private Long databaseId;

    /**
     * 只允许单条受限 SELECT。服务端拒绝 JOIN、CTE、子查询、UNION 和写语句。
     */
    @NotBlank(message = "查询 SQL 不能为空")
    @Size(max = 20000, message = "查询 SQL 过长")
    private String sql;

    @Min(value = 1, message = "返回行数不能小于 1")
    @Max(value = 1000, message = "返回行数不能大于 1000")
    private Integer limit = 100;

    /**
     * 受限字段授权的目标 schema；为空时由服务端按数据源配置解析，不猜测其他 schema。
     */
    @Schema(description = "受限字段授权的目标 schema")
    private String expectedSchema;

    /**
     * 受限字段授权的目标表。与 allowedColumns 必须同时出现；缺一不可降级为无限制查询。
     */
    @Schema(description = "受限字段授权的目标表")
    private String expectedTable;

    /**
     * 允许访问的物理列。出现时服务端在数据库执行前重建投影，越权的返回列、过滤和排序一律拒绝。
     */
    @Schema(description = "允许访问的物理列")
    private List<String> allowedColumns;

    @Schema(description = "动态脱敏场景：DATA_QUERY（默认）/ASSET_PREVIEW/MARKET_PREVIEW/DATA_EXPORT；结果总会经过动态脱敏")
    @jakarta.validation.constraints.Pattern(regexp = "DATA_QUERY|ASSET_PREVIEW|MARKET_PREVIEW|DATA_EXPORT",
            message = "脱敏场景不合法")
    private String maskScene;

}
