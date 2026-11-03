package cn.iocoder.yudao.module.governance.controller.admin.identify.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 敏感数据扫描记录分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class SafetyScanRunPageReqVO extends PageParam {

    @Schema(description = "数据源编号", example = "1")
    private Long databaseId;

    @Schema(description = "运行状态：2-运行中 3-成功 4-失败", example = "3")
    private Integer runStatus;

}
