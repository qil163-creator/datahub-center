package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.task;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "管理后台 - ETL 检测任务创建回执；状态为创建时状态，重试不恢复或覆盖后续修改")
public record QualityEtlTaskCreateRespVO(String requestId, Long taskId, Long checkObjectId,
                                         String taskName, Integer taskStatus, boolean replayed) {

}
