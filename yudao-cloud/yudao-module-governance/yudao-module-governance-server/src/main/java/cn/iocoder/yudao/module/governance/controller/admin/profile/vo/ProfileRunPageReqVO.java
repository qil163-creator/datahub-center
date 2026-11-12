package cn.iocoder.yudao.module.governance.controller.admin.profile.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Schema(description = "管理后台 - 数据探查记录分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class ProfileRunPageReqVO extends PageParam {

    @Schema(description = "元数据表编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    @NotNull(message = "元数据表编号不能为空")
    private Long metaTableId;

    @Schema(description = "状态：PENDING/RUNNING/SUCCESS/FAILED")
    @Pattern(regexp = "PENDING|RUNNING|SUCCESS|FAILED", message = "状态必须是 PENDING/RUNNING/SUCCESS/FAILED")
    private String status;

}
