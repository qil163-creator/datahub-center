package cn.iocoder.yudao.module.governance.controller.admin.identify.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 敏感字段打标分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class SafetyFieldTagPageReqVO extends PageParam {

    @Schema(description = "数据源编号", example = "1")
    private Long databaseId;

    @Schema(description = "表名（模糊）", example = "user")
    private String tableName;

    @Schema(description = "字段名（模糊）", example = "mobile")
    private String columnName;

    @Schema(description = "数据分类编号", example = "1")
    private Long categoryId;

    @Schema(description = "打标状态：PENDING / CONFIRMED / REJECTED", example = "PENDING")
    private String tagStatus;

    @Schema(description = "打标来源：AUTO / MANUAL", example = "AUTO")
    private String source;

    @Schema(description = "扫描记录编号", example = "1")
    private Long scanRunId;

}
