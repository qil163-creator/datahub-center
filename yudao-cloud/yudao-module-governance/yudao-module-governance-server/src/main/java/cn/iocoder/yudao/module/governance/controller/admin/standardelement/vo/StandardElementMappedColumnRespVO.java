package cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 标准数据元落标字段 Response VO")
@Data
public class StandardElementMappedColumnRespVO {

    @Schema(description = "元数据字段编号", example = "1024")
    private Long columnId;

    @Schema(description = "字段名", example = "person_name")
    private String columnName;

    @Schema(description = "业务名称", example = "姓名")
    private String businessName;

    @Schema(description = "数据类型", example = "varchar")
    private String dataType;

    @Schema(description = "数据长度", example = "64")
    private Long dataLength;

    @Schema(description = "是否可空：0-不可空 1-可空", example = "1")
    private Integer nullableFlag;

    @Schema(description = "所属表编号", example = "36")
    private Long tableId;

    @Schema(description = "所属表名", example = "t_person")
    private String tableName;

    @Schema(description = "所属表业务名称", example = "人员信息表")
    private String tableBusinessName;

    @Schema(description = "数仓分层", example = "ODS")
    private String layer;

    @Schema(description = "落标映射编号", example = "1")
    private Long mappingId;

    @Schema(description = "落标映射状态：CONFIRMED/PROPOSED/STALE/REVOKED", example = "CONFIRMED")
    private String mappingStatus;

    @Schema(description = "贯标检查结果：PASS-符合 FAIL-不符合 SKIP-标准未定义约束", example = "PASS")
    private String complianceResult;

    @Schema(description = "贯标检查不符合项说明")
    private List<String> complianceIssues;

}
