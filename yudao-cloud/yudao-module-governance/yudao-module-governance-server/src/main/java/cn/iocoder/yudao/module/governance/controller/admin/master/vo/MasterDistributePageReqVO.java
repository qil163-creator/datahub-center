package cn.iocoder.yudao.module.governance.controller.admin.master.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 主数据派发任务分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class MasterDistributePageReqVO extends PageParam {

    @Schema(description = "最近运行结果：1-等待 2-运行 3-成功 4-失败 5-未知 6-人工处置")
    private Integer latestRunStatus;

    @Schema(description = "派发名称（模糊）", example = "人口")
    private String name;

    @Schema(description = "派发类型：1-数据库 2-API 3-消息队列", example = "1")
    private Integer distributeType;

    @Schema(description = "发布状态：0-未发布 1-已发布", example = "1")
    private Integer status;

    @Schema(description = "主数据模型编号", example = "1")
    private Long masterModelId;

    /** 主数据推送类目，历史任务可为空。 */
    private Long categoryId;

}
