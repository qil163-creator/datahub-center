package cn.iocoder.yudao.module.governance.controller.admin.standardcodeitem.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;



@Schema(description = "管理后台 - 数据标准--码值分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class StandardCodeItemPageReqVO extends PageParam {

    @Schema(description = "码值标签", example = "主汛期")
    @Size(max = 100, message = "码值标签长度不能超过 100 个字符")
    private String label;

    @Schema(description = "码值类型", example = "FSTP")
    @Size(max = 100, message = "码值类型长度不能超过 100 个字符")
    private String dictType;

    @Schema(description = "标准码表编号", example = "1024")
    private Integer codeSetId;

    @Schema(description = "状态", example = "0")
    private Short status;

    @jakarta.validation.constraints.Pattern(regexp = "id|createTime")
    private String sortField = "createTime";

    @jakarta.validation.constraints.Pattern(regexp = "asc|desc")
    private String sortOrder = "desc";
}
