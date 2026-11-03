package cn.iocoder.yudao.module.governance.controller.admin.statictask.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 静态脱敏任务分页 Request VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 静态脱敏任务分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class SafetyStaticTaskPageReqVO extends PageParam {

    @Schema(description = "任务名称，模糊匹配", example = "用户表")
    private String taskName;

    @Schema(description = "源数据源编号", example = "1")
    private Long sourceDatabaseId;

    @Schema(description = "目标数据源编号", example = "2")
    private Long targetDatabaseId;

    @Schema(description = "状态：0-上线、1-下线", example = "0")
    private Integer status;

}
