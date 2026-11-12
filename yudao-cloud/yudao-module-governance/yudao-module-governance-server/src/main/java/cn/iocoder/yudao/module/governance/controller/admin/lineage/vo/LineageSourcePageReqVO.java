package cn.iocoder.yudao.module.governance.controller.admin.lineage.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.validation.InEnum;
import cn.iocoder.yudao.module.governance.enums.lineage.LineageReportStatusEnum;
import cn.iocoder.yudao.module.governance.enums.lineage.LineageSourceTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Schema(description = "管理后台 - 血缘上报来源分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class LineageSourcePageReqVO extends PageParam {

    @Schema(description = "来源类型：DEV_TASK/DATA_ACCESS/INTEGRATION_TASK/ETL_PIPELINE")
    @InEnum(value = LineageSourceTypeEnum.class, message = "来源类型必须是 {value}")
    private String sourceType;

    @Schema(description = "解析状态：SUCCESS/PARTIAL/FAILED/UNSUPPORTED/REMOVED")
    @InEnum(value = LineageReportStatusEnum.class, message = "解析状态必须是 {value}")
    private String status;

    @Schema(description = "关键字：匹配来源名称，纯数字时同时匹配来源编号")
    @Size(max = 128, message = "关键字不能超过 128 个字符")
    private String keyword;

}
