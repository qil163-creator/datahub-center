package cn.iocoder.yudao.module.dataforge.model.dev;

/** 内部作业编排的已校验开发节点引用；不包含脚本、凭据或客户端可选执行身份。 */
public record DevJobNode(Long taskId, Long versionId, Long recordId, Long projectCode, Long workflowCode,
        String workerGroup, String tenantCode, Long environmentCode, boolean terminalGuardRequired) { }
