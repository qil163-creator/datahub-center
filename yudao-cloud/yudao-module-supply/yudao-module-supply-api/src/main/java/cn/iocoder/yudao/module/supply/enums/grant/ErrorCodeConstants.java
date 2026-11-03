package cn.iocoder.yudao.module.supply.enums.grant;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/** Supply 来源授权读取错误；延续现有 1_500_042 业务段，不扩散历史 foundation 包名。 */
public interface ErrorCodeConstants {

    ErrorCode QUOTA_EXHAUSTED = new ErrorCode(1_500_042_020, "批准的消费额度不足，请申请新的额度");
    ErrorCode QUOTA_REQUEST_INVALID = new ErrorCode(1_500_042_021, "消费请求键、来源或计量参数无效");
    ErrorCode QUOTA_REQUEST_CONFLICT = new ErrorCode(1_500_042_022,
            "消费请求已登记；请查看回执，新的查询需使用新的请求键");
    ErrorCode QUOTA_POLICY_CHANGED = new ErrorCode(1_500_042_023, "来源额度与批准记录不一致，禁止重置计数");
    ErrorCode QUOTA_RECEIPT_EXPIRED = new ErrorCode(1_500_042_024, "消费预占已失效，结果不能返回；请查看回执后重新查询");
    ErrorCode GRANT_NOT_EFFECTIVE = new ErrorCode(1_500_042_009, "当前没有有效的来源授权");
    ErrorCode GRANT_SCOPE_CHANGED = new ErrorCode(1_500_042_010, "查询期间授权已变化，请重新发起查询");
    ErrorCode GRANT_SCOPE_UNCONFIRMED = new ErrorCode(1_500_042_011, "来源授权范围无法确认，需要复核");
    ErrorCode GRANT_SUBJECT_INVALID = new ErrorCode(1_500_042_012, "当前授权主体不存在或已停用");
    ErrorCode GRANT_LEGACY_PROTOCOL_REQUIRED = new ErrorCode(1_500_042_013,
            "旧授权协议缺少来源或字段证据，请升级协议或人工复核");
    ErrorCode GRANT_CONTROL_CONFLICT = new ErrorCode(1_500_042_014, "授权控制版本已变化，请刷新后重试");
    ErrorCode GRANT_VALIDITY_INVALID = new ErrorCode(1_500_042_015, "请选择永久授权或提供有效的截止日期");
    ErrorCode GRANT_RPC_PEER_UNVERIFIED = new ErrorCode(1_500_042_016, "来源授权 RPC 未通过服务身份认证");
    ErrorCode GRANT_SOURCE_SYSTEM_MISMATCH = new ErrorCode(1_500_042_017, "来源系统与已认证的服务身份不一致");
}
