package cn.iocoder.yudao.module.governance.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/** 元数据登记错误码的兼容入口。新调用方使用 {@link ErrorCodeConstants}。 */
@Deprecated
public interface MetadataIngestErrorCodes {

    ErrorCode META_INGEST_ENVELOPE_INVALID = ErrorCodeConstants.META_INGEST_ENVELOPE_INVALID;
    ErrorCode META_INGEST_EVENT_CONFLICT = ErrorCodeConstants.META_INGEST_EVENT_CONFLICT;
    ErrorCode META_INGEST_REVISION_CONFLICT = ErrorCodeConstants.META_INGEST_REVISION_CONFLICT;
}
