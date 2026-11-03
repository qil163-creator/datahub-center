package cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - ETL 流程分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class EtlPipelinePageReqVO extends PageParam {

    @Schema(description = "流程名称（模糊）", example = "清洗")
    private String name;

    @Schema(description = "发布状态：0-未发布 1-已发布", example = "1")
    private Integer status;

    @Schema(description = "最近运行状态", example = "3")
    private Integer runStatus;

}
