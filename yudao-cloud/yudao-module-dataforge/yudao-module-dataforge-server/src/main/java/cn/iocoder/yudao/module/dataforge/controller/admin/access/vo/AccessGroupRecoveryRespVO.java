package cn.iocoder.yudao.module.dataforge.controller.admin.access.vo;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "管理后台 - 数据组恢复状态；仅为提示，提交命令时重新校验")
public record AccessGroupRecoveryRespVO(
        @Schema(description = "执行记录编号") Long recordId,
        @Schema(description = "当前记录版本") Long rowVersion,
        @Schema(description = "是否存在本记录的数据组恢复状态") boolean applicable,
        @Schema(description = "当前平台证据是否允许尝试提交恢复命令") boolean ready,
        @Schema(description = "本记录全部已登记数据组是否认证释放，仍须核查原任务输出") boolean released,
        @Schema(description = "恢复状态说明，不包含连接凭据或原始数据") String message) {

}
