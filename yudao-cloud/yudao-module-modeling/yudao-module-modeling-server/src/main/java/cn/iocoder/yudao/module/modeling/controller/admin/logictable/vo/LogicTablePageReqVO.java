package cn.iocoder.yudao.module.modeling.controller.admin.logictable.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 逻辑表分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class LogicTablePageReqVO extends PageParam {

    @Schema(description = "表类型：SOURCE/DIMENSION/DETAIL/SUMMARY/APPLICATION", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "SOURCE")
    @NotBlank(message = "表类型不能为空")
    @Pattern(regexp = "SOURCE|DIMENSION|DETAIL|SUMMARY|APPLICATION", message = "表类型不合法")
    private String tableType;

    @Schema(description = "表名称、英文名或完整表名（模糊）", example = "告警")
    private String name;

    @Schema(description = "来源系统编号", example = "4")
    private Long sourceSystemId;

    @Schema(description = "是否启用：0 禁用，1 启用", example = "1")
    private Integer enabled;

    @Schema(description = "责任人用户编号", example = "1")
    private Long contactUserId;

    @Schema(description = "统计时效", example = "1")
    private String dataTimeliness;

    @Schema(description = "业务类型", example = "1")
    private String businessType;

    @Schema(description = "数据板块编号", example = "29")
    private Long dataDomainId;

    @Schema(description = "主题域编号", example = "113")
    private Long subjectDomainId;

    @Schema(description = "排序字段：id/columnCount/createTime/updateTime", example = "createTime")
    @Pattern(regexp = "id|columnCount|createTime|updateTime", message = "排序字段不合法")
    private String sortField;

    @Schema(description = "是否升序", example = "false")
    private Boolean asc;

}
