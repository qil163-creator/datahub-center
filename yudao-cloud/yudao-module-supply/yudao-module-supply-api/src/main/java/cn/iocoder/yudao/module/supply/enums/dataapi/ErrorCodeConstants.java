package cn.iocoder.yudao.module.supply.enums.dataapi;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/** 开放写操作的持久身份与结果处置，属于 Supply 领域。 */
public interface ErrorCodeConstants {

    ErrorCode OPERATION_KEY_REQUIRED = new ErrorCode(1_500_040_023,
            "写操作必须提供签名保护的业务操作编号 _operationKey");
    ErrorCode OPERATION_CONFLICT = new ErrorCode(1_500_040_024, "该操作编号已绑定其他参数或发布版本，请查询原操作结果");
    ErrorCode OPERATION_NOT_EXISTS = new ErrorCode(1_500_040_025, "操作记录不存在");
    ErrorCode OPERATION_DISPOSITION_CONFLICT = new ErrorCode(1_500_040_026,
            "操作状态已变化或缺少有效核查证据，请刷新后处理");
    ErrorCode OPERATION_SIGN_REQUIRED = new ErrorCode(1_500_040_027, "开放写操作必须使用 v2 签名和已发布快照");
    ErrorCode OPERATION_STORE_UNAVAILABLE = new ErrorCode(1_500_040_028,
            "操作记录暂不可用，未重新执行写入，请查询原操作结果");
}
