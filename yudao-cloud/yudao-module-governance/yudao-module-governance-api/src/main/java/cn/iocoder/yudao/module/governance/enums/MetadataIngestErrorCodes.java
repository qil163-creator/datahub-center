package cn.iocoder.yudao.module.governance.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/** 元数据可靠登记错误码；DataHub 仍共享历史命名空间。 */
public interface MetadataIngestErrorCodes {

    ErrorCode META_INGEST_ENVELOPE_INVALID = new ErrorCode(1_500_047_000, "元数据登记事件信封不完整或版本不支持");
    ErrorCode META_INGEST_EVENT_CONFLICT = new ErrorCode(1_500_047_001, "同一元数据登记事件携带了不同内容");
    ErrorCode META_INGEST_REVISION_CONFLICT = new ErrorCode(1_500_047_002, "同一目标修订号对应多个元数据事件");
}
