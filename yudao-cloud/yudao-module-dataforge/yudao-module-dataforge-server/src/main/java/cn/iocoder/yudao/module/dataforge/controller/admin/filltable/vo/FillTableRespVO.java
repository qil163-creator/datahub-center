package cn.iocoder.yudao.module.dataforge.controller.admin.filltable.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 填报表 Response VO")
@Data
public class FillTableRespVO {

    @Schema(description = "主键id", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    private Long id;

    @Schema(description = "物理表英文名", example = "t_population")
    private String tableEngName;

    @Schema(description = "表中文名", example = "人口信息表")
    private String tableCnName;

    @Schema(description = "描述")
    private String description;

    @Schema(description = "目标数据源id", example = "1")
    private Long databaseId;

    @Schema(description = "目标数据源名称", example = "中台库")
    private String databaseName;

    @Schema(description = "发布状态：0-未发布 1-已发布", example = "1")
    private Integer status;

    @Schema(description = "发布人名称", example = "管理员")
    private String releaseUserName;

    @Schema(description = "发布时间")
    private LocalDateTime releaseTime;

    @Schema(description = "单位id", example = "100")
    private Long orgId;

    @Schema(description = "单位名称", example = "市大数据局")
    private String orgName;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "合并策略：REPLACE_SCOPE/APPEND/UPSERT", example = "REPLACE_SCOPE")
    private String mergeStrategy;

    @Schema(description = "UPSERT 业务键字段码（英文逗号分隔）", example = "student_no")
    private String businessKeyFields;

    @Schema(description = "录入页面冻结的前几列", example = "2")
    private Integer freezeColumnCount;

    @Schema(description = "多级汇总方式：DETAIL-明细汇集 GROUP-分组合计", example = "DETAIL")
    private String rollupMode;

    @Schema(description = "分组合计的分组字段码，英文逗号分隔", example = "region,category")
    private String rollupGroupFields;

}
