package cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 标准数据元影响分析 Response VO")
@Data
public class StandardElementImpactRespVO {

    @Schema(description = "落标字段总数", example = "8")
    private Integer mappedColumnCount;

    @Schema(description = "直接落标的表")
    private List<ImpactTable> directTables;

    @Schema(description = "直接落标表")
    @Data
    public static class ImpactTable {

        @Schema(description = "表编号", example = "36")
        private Long tableId;

        @Schema(description = "表名", example = "t_person")
        private String tableName;

        @Schema(description = "表业务名称", example = "人员信息表")
        private String tableBusinessName;

        @Schema(description = "落标字段数", example = "2")
        private Integer mappedColumnCount;

    }


}
