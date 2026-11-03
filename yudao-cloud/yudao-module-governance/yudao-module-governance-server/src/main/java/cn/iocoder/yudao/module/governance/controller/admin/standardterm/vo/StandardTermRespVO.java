package cn.iocoder.yudao.module.governance.controller.admin.standardterm.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 业务术语 Response VO")
@Data
public class StandardTermRespVO {

    @Schema(description = "主键", example = "1")
    private Long id;

    @Schema(description = "术语名称", example = "客户")
    private String name;

    @Schema(description = "英文名称", example = "customer")
    private String engName;

    @Schema(description = "别名/同义词", example = "顾客,买家")
    private String alias;

    @Schema(description = "业务定义")
    private String definition;

    @Schema(description = "业务术语类目编号", example = "1")
    private Long termCategoryId;

    @Schema(description = "所属类目编号", example = "1")
    private Long categoryId;

    @Schema(description = "关联标准数据元编号", example = "1024")
    private Integer relatedElementId;

    @Schema(description = "负责人用户编号", example = "1")
    private Long ownerUserId;

    @Schema(description = "数据管家用户编号", example = "2")
    private Long stewardUserId;

    @Schema(description = "状态", example = "0")
    private Short status;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
