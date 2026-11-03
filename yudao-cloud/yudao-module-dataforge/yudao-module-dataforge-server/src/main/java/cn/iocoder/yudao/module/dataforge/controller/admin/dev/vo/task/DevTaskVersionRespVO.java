package cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.task;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 开发任务版本 Response VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 开发任务版本 Response VO")
@Data
public class DevTaskVersionRespVO {

    @Schema(description = "主键", example = "1")
    private Long id;

    @Schema(description = "任务编号", example = "1")
    private Long taskId;

    @Schema(description = "版本号", example = "3")
    private Integer version;

    @Schema(description = "版本脚本内容")
    private String content;

    @Schema(description = "版本备注", example = "增加日期过滤")
    private String remark;

    @Schema(description = "创建人", example = "1")
    private String creator;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
