package cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo;

import cn.iocoder.yudao.framework.etl.core.engine.EtlPreviewEngine;

/** 此处不暴露源端授权凭据、源数据行或数据库连接信息。 */
public record EtlPreviewSessionRespVO(String instanceId, String requestId, String mode,
                                      String status, String cleanupStatus, boolean workerExited, long expiresAt,
                                      String message, EtlPreviewEngine.Result result) {

}
