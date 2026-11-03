package cn.iocoder.yudao.module.dataforge.controller.admin.fullsync.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 整库同步配置分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class FullSyncWizardPageReqVO extends PageParam {

    @Schema(description = "任务名称，模糊匹配")
    @Size(max = 200)
    private String taskName;

    @Schema(description = "来源数据库编号")
    @Positive
    private Long sourceDatabaseId;

    @Schema(description = "目标数据库编号")
    @Positive
    private Long targetDatabaseId;
}
