package cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "管理后台 - 子流程已发布定义版本分页")
public class EtlSubflowVersionPageReqVO extends PageParam {

    @NotNull
    @Positive
    @Schema(description = "候选流程编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long pipelineId;

    /**
     * 读取每页行数。
     *
     * @return 每页行数
     */
    @Override
    @Max(value = 50, message = "子流程版本每页最多50条")
    public Integer getPageSize() {
        return super.getPageSize();
    }
}
